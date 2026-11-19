package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Replaces an app with the version in a plan's file. It builds the new version beside the app, as
 * {@code <app>.new-<n>}, and then swaps the two with two renames: {@code <app>} becomes {@code
 * <app>.backup-<n>}, and {@code <app>.new-<n>} becomes {@code <app>}. So {@code <app>} holds
 * either the old or the new version in full, unless the helper is ended between the two renames.
 */
final class Replace {

  /** The list of an app's own files in its folder: one path per line, with {@code /}, in UTF-8. */
  static final String LIST = "rethinkj-updater.files";

  private Replace() {}

  /** The swap began but could not finish, and the old version is back in place. */
  static final class RolledBack extends Exception {
    final String reason;

    RolledBack(String reason, String message) {
      super(message);
      this.reason = reason;
    }
  }

  /**
   * Replaces {@code plan.app()} with the version in {@code plan.file()}, and keeps the old version
   * as {@code <app>.backup-<n>}, whose path it returns.
   *
   * <p>For an archive, a file of the old folder that its list does not name, such as settings the
   * app keeps there, is carried over into the new one, also in place of a file the new version
   * brings. Without a list, the new version's files replace the old ones and nothing is dropped.
   * Nothing changes if such a file would be carried over through a link, as when the new version
   * makes the folder that holds it a link.
   *
   * @param lockedFor how long a rename is tried again while it fails, on Windows because another
   *     process, such as a virus scanner, has a file open
   * @throws Plan.Rejected if nothing changed, with reason {@code checksum}, {@code locked} or
   *     {@code failed}
   * @throws RolledBack with reason {@code locked} if the old version is back in place
   * @throws IOException if the new version cannot be built, so that nothing changed, or if the old
   *     version cannot be put back, and stays in {@code <app>.backup-<n>}
   */
  static Path apply(Plan plan, Duration lockedFor) throws Plan.Rejected, RolledBack, IOException {
    Path app = plan.app();
    boolean archive = plan.kind() == Plan.Kind.ARCHIVE;
    if (archive ? !Files.isDirectory(app) : !Files.isRegularFile(app)) {
      throw new Plan.Rejected("failed", app + " is not a " + (archive ? "folder" : "file"));
    }
    // Hours may have passed since core checked it, with the plan's mode exit.
    String sha256 = sha256(plan.file());
    if (!sha256.equalsIgnoreCase(plan.sha256())) {
      throw new Plan.Rejected(
          "checksum",
          plan.file() + " has the SHA-256 " + sha256 + ", but the plan expects " + plan.sha256());
    }
    // shortcut: the file is checked and then read again; check a copy only the helper can write
    // once it runs with more rights than the user.
    String n = Long.toString(System.currentTimeMillis());
    Path next = app.resolveSibling(app.getFileName() + ".new-" + n);
    Path backup = app.resolveSibling(app.getFileName() + ".backup-" + n);
    try {
      if (archive) {
        Archive.extract(plan.file(), next);
        carryOver(app, next);
      } else {
        try (InputStream in = Files.newInputStream(plan.file())) {
          Files.copy(in, next); // with the permissions of a new file, not those of the download
        }
      }
      swap(app, next, backup, lockedFor);
    } finally {
      delete(next); // after a swap, nothing is left there
    }
    return backup;
  }

  /**
   * Puts the old version in {@code backup} back in place of the app, after the new version did not
   * confirm that it runs, and deletes the new version.
   *
   * @throws IOException if the new version stays in place, or the app's folder is missing
   */
  static void rollBack(Path app, Path backup, Duration lockedFor) throws IOException {
    Path failed = app.resolveSibling(app.getFileName() + ".new-" + System.currentTimeMillis());
    try {
      swap(app, backup, failed, lockedFor);
    } catch (Plan.Rejected | RolledBack | IOException e) {
      // Only as the cause: the message of swap's IOException would call the new version the old.
      throw new IOException("The old version in " + backup + " cannot take the new one's place", e);
    }
    delete(failed);
  }

  /**
   * Renames {@code app} to {@code backup} and {@code next} to {@code app}, each tried again while
   * it fails until {@code lockedFor} has passed. If the second fails, it renames {@code backup}
   * back.
   *
   * @throws Plan.Rejected with reason {@code locked} if the first rename fails
   * @throws RolledBack with reason {@code locked} if the second fails and the old version is back
   * @throws IOException if renaming the old version back fails as well
   */
  static void swap(Path app, Path next, Path backup, Duration lockedFor)
      throws Plan.Rejected, RolledBack, IOException {
    try {
      move(app, backup, lockedFor);
    } catch (IOException e) {
      throw new Plan.Rejected("locked", "Cannot rename " + app + ": " + e);
    }
    try {
      move(next, app, lockedFor);
    } catch (IOException e) {
      try {
        move(backup, app, lockedFor);
      } catch (IOException again) {
        again.addSuppressed(e);
        throw new IOException(
            "The old version is in " + backup + ", which cannot be renamed back to " + app, again);
      }
      throw new RolledBack("locked", "Cannot rename " + next + " to " + app + ": " + e);
    }
  }

  /** Writes the list of the files in {@code folder} into it, replacing an earlier one. */
  static void writeList(Path folder) throws IOException {
    writeList(folder, files(folder));
  }

  /**
   * Copies the files of {@code app} that its list does not name into {@code next}, which holds the
   * unpacked new version, and writes the list of the new version's own files into {@code next}.
   */
  private static void carryOver(Path app, Path next) throws IOException {
    Path list = app.resolve(LIST);
    Set<String> listed = Files.isRegularFile(list) ? new HashSet<>(Files.readAllLines(list)) : null;
    TreeSet<String> own = files(next);
    try (Stream<Path> paths = Files.walk(app)) {
      for (Path file : (Iterable<Path>) paths::iterator) {
        String name = name(app.relativize(file));
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
            || name.equals(LIST)
            || (listed != null && listed.contains(name))) {
          continue;
        }
        Path target = next.resolve(app.relativize(file));
        if (listed == null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          continue; // without a list, the old version's files are not told from the user's
        }
        // Never through a link, of the new version or of the user's carried over before: it could
        // lead out of the new version, or two files to one.
        Path folder = next;
        for (Path part : app.relativize(file.getParent())) {
          folder = folder.resolve(part);
          if (Files.isSymbolicLink(folder)) {
            throw new IOException(file + " cannot be carried over through the link " + folder);
          }
        }
        Files.createDirectories(target.getParent());
        Files.copy(
            file,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
            LinkOption.NOFOLLOW_LINKS);
        own.remove(name); // the user's now, so a later update keeps it as well
      }
    }
    writeList(next, own);
  }

  /** The paths of the files in {@code folder}, without the list. */
  private static TreeSet<String> files(Path folder) throws IOException {
    try (Stream<Path> paths = Files.walk(folder)) {
      return paths
          .filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
          .map(p -> name(folder.relativize(p)))
          .filter(name -> !name.equals(LIST))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static void writeList(Path folder, Set<String> files) throws IOException {
    Files.writeString(
        folder.resolve(LIST), files.stream().map(f -> f + "\n").collect(Collectors.joining()));
  }

  /** A relative path with {@code /} between its parts, as the list holds it. */
  private static String name(Path relative) {
    return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
  }

  private static void move(Path from, Path to, Duration lockedFor) throws IOException {
    long end = System.nanoTime() + lockedFor.toNanos();
    while (true) {
      try {
        Files.move(from, to); // within one folder a rename, and it fails if "to" exists
        return;
      } catch (IOException e) {
        if (System.nanoTime() - end >= 0) {
          throw e;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /**
   * Deletes a version beside the app, as far as it can; what is left there is inert. Of a link or a
   * Windows junction in it, it deletes only the link.
   */
  static void delete(Path version) {
    try {
      Files.walkFileTree(version, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            throws IOException {
          if (attrs.isOther()) { // a junction, which walkFileTree would enter
            Files.delete(dir);
            return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (attrs instanceof DosFileAttributes dos && dos.isReadOnly()) {
            // Else Windows refuses to delete it, such as the legal notices of a jlink runtime.
            Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
          }
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
          if (e != null) {
            throw e;
          }
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      // Also when there is no such folder, after a swap.
    }
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime has SHA-256", e);
    }
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
