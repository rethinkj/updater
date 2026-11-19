package com.rethinkj.updater.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.Installation;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyUpdateTest {

  private static final boolean WINDOWS = File.separatorChar == '\\';

  @TempDir
  Path temp;

  @Test
  void theHelperReplacesTheAppOnceItHasExitedAndCoreReportsIt() throws Exception {
    // Version 1.2.0 as jpackage lays it out, with its own runtime in its folder.
    Path app = temp.resolve("Bücher App");
    Path runtime = app.resolve(WINDOWS ? "runtime" : "lib/runtime");
    Path launcher = app.resolve(WINDOWS ? "Bücher App.exe" : "bin/Bücher App");
    Files.createDirectories(runtime.getParent());
    jlink(runtime);
    Files.writeString(app.resolve("version.txt"), "1.2.0");
    Path updates = Files.createDirectories(temp.resolve("Bücher App updates"));
    Path zip = zip(updates.resolve("download-1.3.0.zip"), "version.txt", "1.3.0");
    Path log = temp.resolve("app.log");

    Process process = new ProcessBuilder(
            runtime.resolve("bin").resolve(WINDOWS ? "java.exe" : "java").toString(),
            "-Djpackage.app-path=" + launcher,
            "-cp",
            System.getProperty("java.class.path"),
            ApplyUpdate.class.getName(),
            updates.toString(),
            zip.toString(),
            sha256(zip))
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    assertEquals(0, process.waitFor(), () -> read(log));

    Path result = updates.resolve("result.properties");
    for (long end = System.nanoTime() + 60_000_000_000L; !Files.exists(result); ) {
      assertTrue(
          System.nanoTime() < end, () -> "No result; " + read(updates.resolve("helper.log")));
      Thread.sleep(100);
    }
    Installation installation = new Installation(app, updates, List.of());
    Installation.Result reported = installation.result().orElseThrow();

    assertEquals(Installation.Result.Status.APPLIED, reported.status(), reported::message);
    assertEquals("1.3.0", Files.readString(app.resolve("version.txt")));
    try (Stream<Path> beside = Files.list(temp)) {
      assertEquals(
          List.of(), beside.filter(p -> p.toString().contains(".backup-")).toList());
    }
  }

  /** A runtime of java.base alone, which is all core needs to start the helper. */
  private static void jlink(Path runtime) {
    ToolProvider jlink = ToolProvider.findFirst("jlink").orElseThrow();
    String[] args = {"--add-modules", "java.base", "--output", runtime.toString()};
    assertEquals(0, jlink.run(System.out, System.err, args));
  }

  private static Path zip(Path zip, String name, String content) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry(name));
      out.write(content.getBytes());
    }
    return zip;
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return "no " + file;
    }
  }
}
