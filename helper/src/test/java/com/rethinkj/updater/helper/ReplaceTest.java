package com.rethinkj.updater.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplaceTest {

  @TempDir
  Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void replacesTheAppAndKeepsTheFilesItsListDoesNotName(boolean listed) throws Exception {
    Path app = oldVersion();
    if (listed) {
      Helper.main(new String[] {"list", app.toString()});
    }
    write(app, "settings.ini", "mine");
    write(app, "conf/app.properties", "mine"); // also in the new version
    Path zip = zip(
        "Bücher App-1.3.0/bin/app", "1.3.0",
        "Bücher App-1.3.0/lib/app.jar", "1.3.0",
        "Bücher App-1.3.0/conf/app.properties", "1.3.0");

    Path plan = plan(app, "archive", zip, sha256(zip));
    Properties result = run(plan);

    assertEquals("applied", result.getProperty("status"));
    assertNull(result.getProperty("reason"));
    // For core, should the helper be ended before the result; core reads it with this key.
    Properties backup = load(plan.resolveSibling("backup.properties"));
    assertEquals("1", backup.getProperty("format"));
    assertTrue(backup.getProperty("backup").startsWith(app + ".backup-"));
    assertEquals("1.3.0", Files.readString(app.resolve("bin/app")));
    assertEquals("1.3.0", Files.readString(app.resolve("lib/app.jar")));
    assertEquals("mine", Files.readString(app.resolve("settings.ini")));
    if (listed) {
      assertFalse(Files.exists(app.resolve("lib/old.jar")));
      assertEquals("mine", Files.readString(app.resolve("conf/app.properties")));
      assertEquals("bin/app\nlib/app.jar\n", Files.readString(app.resolve(Replace.LIST)));
    } else {
      assertEquals("1.2.0", Files.readString(app.resolve("lib/old.jar")));
      assertEquals("1.3.0", Files.readString(app.resolve("conf/app.properties")));
      assertEquals(
          "bin/app\nconf/app.properties\nlib/app.jar\n",
          Files.readString(app.resolve(Replace.LIST)));
    }
    assertNothingBeside(app); // the old version is deleted once the new one is in place
  }

  /** The new version makes conf, where the user keeps a file, a link to settings. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a link needs rights there")
  void changesNothingIfAFileWouldBeCarriedOverThroughALink(boolean listed) throws Exception {
    Path app = oldVersion();
    if (listed) {
      Helper.main(new String[] {"list", app.toString()});
    }
    write(app, "conf/user.ini", "mine");
    Path tar = Path.of("src/test/resources/archive/settings.tar.gz");

    Properties result = run(plan(app, "archive", tar, sha256(tar)));

    assertEquals("rejected", result.getProperty("status"));
    assertOldVersionAlone(app);
    assertEquals("mine", Files.readString(app.resolve("conf/user.ini")));
  }

  @Test
  void namesNoBackupForCoreOnceTheNewVersionFailsToConfirm() throws Exception {
    Path app = oldVersion();
    Path zip = zip("Bücher App-1.3.0/bin/app", "1.3.0");
    Path plan = plan(app, "archive", zip, sha256(zip));
    Properties restart = load(plan);
    restart.setProperty("mode", "restart");
    restart.setProperty("command.0", temp.resolve("no launcher").toString());
    restart.setProperty("confirm.seconds", "120");
    try (Writer out = Files.newBufferedWriter(plan, StandardCharsets.UTF_8)) {
      restart.store(out, null);
    }

    Properties result = run(plan);

    assertEquals("rolled-back", result.getProperty("status"));
    assertEquals("not-confirmed", result.getProperty("reason"));
    assertFalse(Files.exists(plan.resolveSibling("backup.properties")));
    assertOldVersionAlone(app);
  }

  @Test
  void changesNothingWithoutTheLockThatTellsCoreItIsAtWork() throws Exception {
    Path app = oldVersion();
    Path zip = zip("Bücher App-1.3.0/bin/app", "1.3.0");
    Files.createDirectories(temp.resolve("updates/helper.lock")); // a folder cannot be locked

    Properties result = run(plan(app, "archive", zip, sha256(zip)));

    assertEquals("rejected", result.getProperty("status"));
    assertEquals("failed", result.getProperty("reason"));
    assertOldVersionAlone(app);
  }

  @Test
  void replacesASingleJar() throws Exception {
    Path app = write(temp, "Bücher App.jar", "1.2.0");
    Path jar = write(temp, "updates/Bücher App-1.3.0.jar", "1.3.0");

    Properties result = run(plan(app, "jar", jar, sha256(jar)));

    assertEquals("applied", result.getProperty("status"));
    assertEquals("1.3.0", Files.readString(app));
    assertNothingBeside(app);
  }

  @Test
  void changesNothingIfTheDownloadDoesNotMatchItsChecksum() throws Exception {
    Path app = oldVersion();
    Path zip = zip("bin/app", "1.3.0");

    Properties result = run(plan(app, "archive", zip, "0".repeat(64)));

    assertEquals("rejected", result.getProperty("status"));
    assertEquals("checksum", result.getProperty("reason"));
    assertOldVersionAlone(app);
  }

  @Test
  void changesNothingIfTheNewVersionFailsHalfwayThroughUnpacking() throws Exception {
    Path app = oldVersion();
    // A file lib, and then a file in a folder lib, which cannot be.
    Path zip = zip("bin/app", "1.3.0", "lib", "1.3.0", "lib/app.jar", "1.3.0");

    Properties result = run(plan(app, "archive", zip, sha256(zip)));

    assertEquals("rejected", result.getProperty("status"));
    assertEquals("failed", result.getProperty("reason"));
    assertOldVersionAlone(app);
  }

  @Test
  void renamesTheOldVersionBackIfTheNewOneCannotTakeItsPlace() throws Exception {
    Path app = oldVersion();
    Path backup = temp.resolve("Bücher App.backup-1");

    assertThrows(
        Replace.RolledBack.class,
        () -> Replace.swap(app, temp.resolve("missing"), backup, Duration.ZERO));

    assertOldVersionAlone(app);
  }

  @Test
  void deletesAnOldVersionButNotWhatALinkInItLeadsTo() throws Exception {
    Path backup =
        write(temp, "Bücher App.backup-1/bin/app", "1.2.0").getParent().getParent();
    Path outside = write(temp, "outside/data", "mine").getParent();
    Path link = backup.resolve("data");
    if (File.separatorChar == '\\') { // a junction: no rights needed, and walkFileTree enters it
      String[] mklink = {"cmd", "/c", "mklink", "/J", link.toString(), outside.toString()};
      assertEquals(0, new ProcessBuilder(mklink).start().waitFor());
    } else {
      Files.createSymbolicLink(link, outside);
    }

    Replace.delete(backup);

    assertFalse(Files.exists(backup));
    assertEquals("mine", Files.readString(outside.resolve("data")));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void triesAgainWhileAFileIsLockedAndChangesNothingIfItStaysLocked() throws Exception {
    Path app = oldVersion();
    Path zip = zip("Bücher App-1.3.0/bin/app", "1.3.0");
    Plan plan = Plan.read(plan(app, "archive", zip, sha256(zip)));
    // java.io opens a file without FILE_SHARE_DELETE, as a virus scanner may: then neither the
    // file nor its folders can be renamed.
    InputStream lock = new FileInputStream(app.resolve("lib/old.jar").toFile());
    try (lock) {
      Plan.Rejected e =
          assertThrows(Plan.Rejected.class, () -> Replace.apply(plan, Duration.ofMillis(300)));
      assertEquals("locked", e.reason);
      assertOldVersionAlone(app);

      CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> close(lock));
      Replace.apply(plan, Duration.ofSeconds(10));
    }

    assertEquals("1.3.0", Files.readString(app.resolve("bin/app")));
  }

  /** Version 1.2.0 of an app in a folder whose name has a space and a character beyond ASCII. */
  private Path oldVersion() throws IOException {
    Path app = temp.resolve("Bücher App");
    write(app, "bin/app", "1.2.0");
    write(app, "lib/old.jar", "1.2.0");
    return app;
  }

  /** Asserts that the app's folder holds version 1.2.0 and nothing beside it was left over. */
  private void assertOldVersionAlone(Path app) throws IOException {
    assertEquals("1.2.0", Files.readString(app.resolve("bin/app")));
    assertEquals("1.2.0", Files.readString(app.resolve("lib/old.jar")));
    try (Stream<Path> files = Files.list(temp)) {
      assertEquals(
          Set.of("Bücher App"),
          files
              .map(p -> p.getFileName().toString())
              .filter(name -> !name.equals("updates"))
              .collect(Collectors.toSet()));
    }
  }

  private static void assertNothingBeside(Path app) throws IOException {
    try (Stream<Path> files = Files.list(app.getParent())) {
      assertEquals(
          List.of(),
          files
              .filter(p -> p.getFileName().toString().startsWith(app.getFileName() + "."))
              .toList());
    }
  }

  /** Writes a plan of mode exit into {@code updates}, as core writes it. */
  private Path plan(Path app, String kind, Path file, String sha256) throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("pid", Long.toString(exited()));
    p.setProperty("app", app.toString());
    p.setProperty("kind", kind);
    p.setProperty("file", file.toString());
    p.setProperty("sha256", sha256);
    p.setProperty("mode", "exit");
    Path plan = Files.createDirectories(temp.resolve("updates")).resolve("plan.properties");
    try (Writer out = Files.newBufferedWriter(plan, StandardCharsets.UTF_8)) {
      p.store(out, null);
    }
    return plan;
  }

  private static Properties run(Path plan) throws IOException {
    Helper.run(plan);
    return load(plan.resolveSibling("result.properties"));
  }

  private static Properties load(Path file) throws IOException {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(in);
    }
    return p;
  }

  /** The ID of a process that has exited, for the helper not to wait. */
  private static long exited() throws IOException {
    Process java = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
    java.onExit().join();
    return java.pid();
  }

  /** A zip in {@code updates} of names, each followed by what the file holds. */
  private Path zip(String... entries) throws IOException {
    Path zip = Files.createDirectories(temp.resolve("updates")).resolve("Bücher App-1.3.0.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (int i = 0; i < entries.length; i += 2) {
        out.putNextEntry(new ZipEntry(entries[i]));
        out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
      }
    }
    return zip;
  }

  private static Path write(Path folder, String name, String text) throws IOException {
    Path file = folder.resolve(name);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, text);
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static void close(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
