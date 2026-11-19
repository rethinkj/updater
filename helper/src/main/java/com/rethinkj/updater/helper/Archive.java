package com.rethinkj.updater.helper;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
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
  private interface Visitor {
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
    boolean posix = target.getFileSystem().supportedFileAttributeViews().contains("posix");
    for (Map.Entry<String, String> link : links.entrySet()) {
      List<String> parts = parts(link.getKey());
      Path at = Path.of("", parts.subList(strip, parts.size()).toArray(String[]::new));
      Path to = Path.of(link.getValue());
      if (to.getRoot() != null || at.resolveSibling(to).normalize().startsWith("..")) {
        throw new IOException(link.getKey() + " links out of the folder it is unpacked into");
      }
    }

    Files.createDirectory(target);
    Map<Path, Path> made = new LinkedHashMap<>();
    read(archive, (name, folder, mode, link, data) -> {
      List<String> parts = parts(name);
      if (parts.size() <= strip) {
        return;
      }
      Path file = target;
      try {
        for (String part : parts.subList(strip, parts.size())) {
          file = file.resolve(part);
        }
      } catch (InvalidPathException e) { // a character this system does not allow in a name
        throw new IOException("Cannot unpack " + name + ": " + e.getMessage(), e);
      }
      if (folder) {
        Files.createDirectories(file);
        return;
      }
      Files.createDirectories(file.getParent());
      if (link != null) {
        made.put(file, Path.of(link)); // written last, so that no entry is written through one
        return;
      }
      Files.copy(data, file);
      if (mode >= 0 && posix) {
        // Never writable by others: the helper may run with more rights than the user.
        Files.setPosixFilePermissions(file, permissions(mode & 0755));
      }
    });
    for (Map.Entry<Path, Path> link : made.entrySet()) {
      Files.createSymbolicLink(link.getKey(), link.getValue());
    }
    // Through another link, such as one to a folder further up, a link can still lead out.
    Path root = target.toRealPath();
    for (Path link : made.keySet()) {
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
        readTar(in, visitor);
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

  /** The end of a tar archive. A name alone does not tell it: a ustar name may be all prefix. */
  private static final byte[] END = new byte[512];

  private static void readTar(InputStream in, Visitor visitor) throws IOException {
    byte[] header = new byte[512];
    String longName = null; // from a GNU or PAX header, for the entry that follows it
    while (true) {
      if (in.readNBytes(header, 0, 512) < 512) {
        throw new EOFException("The tar archive ends without its end marker");
      }
      if (Arrays.equals(header, END)) {
        return;
      }
      long size = number(header, 124, 12);
      Data data = new Data(in, size);
      char type = (char) header[156];
      String name = longName != null ? longName : name(header);
      longName = null;
      switch (type) {
        case '0', '\0', '7' -> visitor.visit(name, false, (int) number(header, 100, 8), null, data);
        case '5' -> visitor.visit(name, true, -1, null, data);
        // shortcut: a target over 100 bytes is rejected from GNU tar and, cut short, leads to
        // nothing from PAX; read their long targets once an app has such a link.
        case '2' -> visitor.visit(name, false, -1, string(header, 157, 100), data);
        case 'L' -> {
          byte[] bytes = data.readAllBytes();
          longName = string(bytes, 0, bytes.length);
        }
        case 'x' -> longName = paxPath(data.readAllBytes());
        case 'g' -> { // PAX values for all entries; paxPath rejects a size or sparse map in them
          if (paxPath(data.readAllBytes()) != null) {
            throw new IOException("The tar archive gives all its files one path");
          }
        }
        default ->
          throw new IOException(name + " is a link or a special file, not a file or folder");
      }
      in.skipNBytes(data.left + (-size & 511)); // what was not read, and the rest of the block
    }
  }

  /** The name in a ustar header, with its prefix, which GNU tar uses for other things. */
  private static String name(byte[] header) {
    String name = string(header, 0, 100);
    boolean ustar = string(header, 257, 6).equals("ustar");
    String prefix = ustar ? string(header, 345, 155) : "";
    return prefix.isEmpty() ? name : prefix + "/" + name;
  }

  /** The {@code path} in PAX records, {@code "<length> <key>=<value>\n"}, or null. */
  private static String paxPath(byte[] records) throws IOException {
    String path = null;
    try {
      for (int i = 0; i < records.length; ) {
        int space = i;
        while (records[space] != ' ') {
          space++;
        }
        int length = Integer.parseInt(new String(records, i, space - i, StandardCharsets.US_ASCII));
        String record =
            new String(records, space + 1, i + length - space - 2, StandardCharsets.UTF_8);
        if (record.startsWith("path=")) {
          path = record.substring(5);
        } else if (record.startsWith("size=") || record.startsWith("GNU.sparse.")) {
          // A file over 8 GB, which no app has, or one packed with tar --sparse.
          throw new IOException(
              "The tar archive holds a file over 8 GB or a sparse file, which the helper does not"
                  + " read");
        }
        i += length;
      }
    } catch (RuntimeException e) { // a bad length
      throw new IOException("The tar archive has a bad PAX header", e);
    }
    return path;
  }

  /** An octal number in a tar header, ended by a space or NUL. */
  private static long number(byte[] header, int offset, int length) throws IOException {
    String digits = string(header, offset, length).trim();
    try {
      return digits.isEmpty() ? 0 : Long.parseLong(digits, 8);
    } catch (NumberFormatException e) { // also the base-256 sizes of files over 8 GB
      throw new IOException("The tar archive has a bad header", e);
    }
  }

  /** The text in a field up to its first NUL. */
  private static String string(byte[] bytes, int offset, int length) {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) {
      end++;
    }
    return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
  }

  private static Set<PosixFilePermission> permissions(int mode) {
    Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
    for (PosixFilePermission p : PosixFilePermission.values()) { // in the order of the bits
      if ((mode & (0400 >> p.ordinal())) != 0) {
        permissions.add(p);
      }
    }
    return permissions;
  }

  /** The data of one tar entry: the next {@code left} bytes of the archive. */
  private static final class Data extends InputStream {
    private final InputStream in;
    private long left;

    Data(InputStream in, long size) {
      this.in = in;
      this.left = size;
    }

    @Override
    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left == 0) {
        return -1;
      }
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n < 0) {
        throw new EOFException("The tar archive ends inside an entry");
      }
      left -= n;
      return n;
    }
  }
}
