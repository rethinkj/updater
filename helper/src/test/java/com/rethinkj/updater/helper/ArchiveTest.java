package com.rethinkj.updater.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchiveTest {

  private static final Path ARCHIVES = Path.of("src/test/resources/archive");

  @TempDir
  Path temp;

  /**
   * The archives hold one folder, {@code Bücher App-1.3.0}, in which the JAR has mode 0664. {@code
   * gnu} and {@code pax} are made with GNU tar 1.35, {@code --owner=0 --group=0 --numeric-owner}
   * and {@code --format=gnu -C <parent> .}, so that its names start with {@code ./}, or {@code
   * --format=posix}. {@code ustar} is made with Python 3.12's {@code tarfile} and {@code
   * USTAR_FORMAT}, which leaves the name field of the JAR's folder empty and puts its whole path
   * into the prefix. Each stores the long path of the JAR in its own way.
   */
  @ParameterizedTest
  @ValueSource(strings = {"gnu", "pax", "ustar"})
  void unpacksATarGzIntoTheFolderWithItsFilesExecutable(String format) throws IOException {
    Path app = temp.resolve("Bücher App");

    Archive.extract(ARCHIVES.resolve(format + ".tar.gz"), app);

    Path launcher = app.resolve("bin/Bücher App");
    Path jar = app.resolve("lib")
        .resolve(
            "a-folder-whose-name-of-a-hundred-characters-fits-a-tar-header-only-without-the-slash-at-its-end-0100")
        .resolve("app.jar");
    assertEquals("#!/bin/sh\n", Files.readString(launcher));
    assertEquals("module\n", Files.readString(jar));
    if (app.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(
          PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(launcher));
      assertEquals(
          PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(jar));
    }
  }

  @Test
  void unpacksAZipWithoutATopFolderAsItIs() throws IOException {
    Path app = temp.resolve("Bücher App");

    Archive.extract(zip("bin/Bücher App.exe", "lib/app.jar"), app);

    assertEquals("bin/Bücher App.exe", Files.readString(app.resolve("bin/Bücher App.exe")));
    assertEquals("lib/app.jar", Files.readString(app.resolve("lib/app.jar")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"../evil", "bin/../../evil", "..\\evil", "bin/.../evil", "/evil", "C:/evil"})
  void rejectsAnEntryThatLeadsOutOfTheFolder(String name) throws IOException {
    Path zip = zip("bin/app", name);
    Path app = temp.resolve("app");

    IOException e = assertThrows(IOException.class, () -> Archive.extract(zip, app));

    assertEquals(name + " leads out of the folder it is unpacked into", e.getMessage());
    assertFalse(Files.exists(app));
    assertFalse(Files.exists(temp.resolve("evil")));
  }

  @Test
  void rejectsALink() {
    Path app = temp.resolve("app");

    // app/passwd links to /etc/passwd.
    IOException e = assertThrows(
        IOException.class, () -> Archive.extract(ARCHIVES.resolve("link.tar.gz"), app));

    assertEquals("app/passwd is a link or a special file, not a file or folder", e.getMessage());
    assertFalse(Files.exists(app));
  }

  /** A zip in which each entry holds its own name. */
  private Path zip(String... names) throws IOException {
    Path zip = temp.resolve("app.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (String name : names) {
        out.putNextEntry(new ZipEntry(name));
        out.write(name.getBytes(StandardCharsets.UTF_8));
      }
    }
    return zip;
  }
}
