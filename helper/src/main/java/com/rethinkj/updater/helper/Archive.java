package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks the archive of an app: a zip, as on Windows, or a tar.gz, as on Linux, where only tar
 * keeps which files are executable. tar is read in the ustar format, with long names as GNU tar
 * and PAX write them.
 */
final class Archive {

  private Archive() {}

  /** What a pass over an archive is given for each entry. */
  @FunctionalInterface
  interface Visitor {
    /**
     * @param mode the entry's Unix permissions, or -1 in a zip, which {@code java.util.zip} does not
     *     tell
     * @param link the target of a symbolic link in a tar, or null
     */
    void visit(String name, boolean folder, int mode, String link, InputStream data)
        throws IOException;
  }

  /**
   * Unpacks {@code archive} into {@code target}, which it creates. If the archive has a single top
   * folder, such as {@code myapp-1.3.0/}, its contents end up in {@code target}. Files from a tar
   * keep their permissions, except that only their owner may write them.
   *
   * <p>A symbolic link in a tar stays a link, as long as it leads to a file or folder in {@code
   * target}, such as those jlink makes between the legal notices of a runtime. A link in a zip
   * comes out as a file holding the link's target, since {@code java.util.zip} cannot tell it from
   * one.
   *
   * <p>It writes nothing if an entry would lead out of {@code target}, through {@code ..}, an
   * absolute path or a drive letter, if a link's target does so, or if it is a hard link or a
   * special file. A link that leads out only through another link, or to nothing, is found once
   * the links are written.
   *
   * @param target a folder that does not exist yet; it may hold part of the archive if writing
   *     fails
   * @throws IOException if the archive cannot be read, holds such an entry, or cannot be written
   */
  static void extract(Path archive, Path target) throws IOException {
    int strip = check(archive); // before anything is written
    boolean posix = target.getFileSystem().supportedFileAttributeViews().contains("posix");
    Files.createDirectory(target);
    Map<Path, Path> made =
        new LinkedHashMap<>(); // written last, so that no entry is written through one
    read(archive, (name, folder, mode, link, data) -> {
      List<String> parts = parts(name);
      if (parts.size() <= strip) {
        return;
      }
      Path file = resolve(target, name, parts.subList(strip, parts.size()));
      if (folder) {
        Files.createDirectories(file);
      } else if (link != null) {
        Files.createDirectories(file.getParent());
        made.put(file, Path.of(link));
      } else {
        Files.createDirectories(file.getParent());
        Files.copy(data, file);
        if (mode >= 0 && posix) {
          // Never writable by others: the helper may run with more rights than the user.
          Files.setPosixFilePermissions(file, permissions(mode & 0_755));
        }
      }
    });
    link(target, made);
  }

  /**
   * Checks every name and link target in {@code archive} without writing anything.
   *
   * @return how many folders to strip from each name: 1 for a single top folder, else 0
   */
  private static int check(Path archive) throws IOException {
    // A single top folder is one name ending in "/"; a file at the top is one without.
    Set<String> tops = new HashSet<>();
    Map<String, String> links = new LinkedHashMap<>(); // the names of the links, and their targets
    read(archive, (name, folder, mode, link, data) -> {
      List<String> parts = parts(name);
      if (!parts.isEmpty()) {
        tops.add(folder || parts.size() > 1 ? parts.getFirst() + "/" : parts.getFirst());
      }
      if (link != null) {
        links.put(name, link);
      }
    });
    int strip = tops.size() == 1 && tops.iterator().next().endsWith("/") ? 1 : 0;
    for (Map.Entry<String, String> link : links.entrySet()) {
      List<String> parts = parts(link.getKey());
      Path at = Path.of("", parts.subList(strip, parts.size()).toArray(String[]::new));
      Path to = Path.of(link.getValue());
      if (to.getRoot() != null || at.resolveSibling(to).normalize().startsWith("..")) {
        throw new IOException(link.getKey() + " links out of the folder it is unpacked into");
      }
    }
    return strip;
  }

  /** The path of an entry in {@code target}, from the parts of its name that are left. */
  private static Path resolve(Path target, String name, List<String> parts) throws IOException {
    Path file = target;
    try {
      for (String part : parts) {
        file = file.resolve(part);
      }
    } catch (InvalidPathException e) { // a character this system does not allow in a name
      throw new IOException("Cannot unpack " + name + ": " + e.getMessage(), e);
    }
    return file;
  }

  /** Writes the links, then checks that each leads to a file or folder in {@code target}. */
  private static void link(Path target, Map<Path, Path> links) throws IOException {
    for (Map.Entry<Path, Path> link : links.entrySet()) {
      Files.createSymbolicLink(link.getKey(), link.getValue());
    }
    // Through another link, such as one to a folder further up, a link can still lead out.
    Path root = target.toRealPath();
    for (Path link : links.keySet()) {
      if (!Files.exists(link) || !link.toRealPath().startsWith(root)) {
        throw new IOException(target.relativize(link)
            + " links out of the folder it is unpacked into, or to nothing");
      }
    }
  }

  /**
   * The parts of an entry's name, without empty and {@code .} parts.
   *
   * @throws IOException if the name leads out of the folder it is unpacked into
   */
  private static List<String> parts(String name) throws IOException {
    if (name.startsWith("/") || name.startsWith("\\")) {
      throw new IOException(name + " leads out of the folder it is unpacked into");
    }
    List<String> parts = new ArrayList<>();
    for (String part : name.split("[/\\\\]")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      // ".." and, since Windows drops dots and spaces at the end of a name, "..." or ".. " too.
      if (part.contains(":") || part.matches("[. ]+")) {
        throw new IOException(name + " leads out of the folder it is unpacked into");
      }
      parts.add(part);
    }
    return parts;
  }

  private static void read(Path archive, Visitor visitor) throws IOException {
    byte[] start;
    try (InputStream in = Files.newInputStream(archive)) {
      start = in.readNBytes(2);
    }
    if (start.length == 2 && start[0] == 0x1f && start[1] == (byte) 0x8b) { // gzip
      try (InputStream file = Files.newInputStream(archive);
          InputStream in = new GZIPInputStream(file, 1 << 16)) {
        Tar.read(in, visitor);
        in.transferTo(OutputStream.nullOutputStream()); // only at its end does gzip check its CRC
      }
      return;
    }
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      for (ZipEntry entry : Collections.list(zip.entries())) {
        try (InputStream data = zip.getInputStream(entry)) {
          visitor.visit(entry.getName(), entry.isDirectory(), -1, null, data);
        }
      }
    }
  }

  private static Set<PosixFilePermission> permissions(int mode) {
    Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
    for (PosixFilePermission p : PosixFilePermission.values()) { // in the order of the bits
      if ((mode & (0_400 >> p.ordinal())) != 0) {
        permissions.add(p);
      }
    }
    return permissions;
  }
}
