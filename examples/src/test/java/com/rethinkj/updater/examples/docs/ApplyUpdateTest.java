package com.rethinkj.updater.examples.docs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.Download;
import com.rethinkj.updater.Installation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Updates an app image that jpackage built, with its own runtime, through its launcher: the way
 * an app ships, on Linux and Windows alike.
 */
class ApplyUpdateTest {

  private static final boolean WINDOWS = File.separatorChar == '\\';

  @TempDir
  static Path shared;

  private static Path runtime;
  private static Path input;

  @TempDir
  Path temp;

  private Path app;
  private Path updates;

  /** A runtime of java.base alone, and the JARs the app ships with. */
  @BeforeAll
  static void prepare() throws Exception {
    runtime = shared.resolve("runtime");
    tool("jlink", "--add-modules", "java.base", "--output", runtime.toString());
    input = Files.createDirectories(shared.resolve("input"));
    Path classes = Path.of(
        ApplyUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    tool(
        "jar",
        "--create",
        "--file",
        input.resolve("examples.jar").toString(),
        "-C",
        classes.toString(),
        ".");
    Path core = Path.of(
        Installation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Files.copy(core, input.resolve("core.jar"));
    var helper =
        (JarURLConnection) ClassLoader.getSystemResource("com/rethinkj/updater/helper/Helper.class")
            .openConnection();
    Files.copy(Path.of(helper.getJarFileURL().toURI()), input.resolve("helper.jar"));
  }

  /** On Windows the temporary folder can be deleted only once no process runs from it. */
  @AfterEach
  void waitForProcesses() throws InterruptedException {
    waitFor(() -> ProcessHandle.allProcesses()
        .noneMatch(
            p -> p.info().command().filter(c -> c.startsWith(temp.toString())).isPresent()));
  }

  @Test
  void restartsTheNewVersionAndKeepsItOnceItConfirms() throws Exception {
    Process old = updateTo("1.3.0");

    assertEquals(0, old.waitFor());
    Path result = updates.resolve("result.properties");
    waitFor(() -> Files.exists(result));
    Installation.Result reported =
        new Installation(app, updates, List.of()).result().orElseThrow();
    assertEquals(Installation.Result.Status.APPLIED, reported.status(), reported::message);
    assertEquals("1.3.0", Files.readString(app.resolve("version.txt")));
    assertNothingBeside();
  }

  @Test
  void putsTheOldVersionBackAndStartsItIfTheNewOneExitsWithoutConfirming() throws Exception {
    Process old = updateTo(App.BROKEN);

    assertEquals(0, old.waitFor());
    Path log = temp.resolve("app.log");
    waitFor(() -> read(log).contains("ROLLED_BACK not-confirmed"));
    assertEquals("1.2.0", Files.readString(app.resolve("version.txt")));
    assertNothingBeside();
  }

  /**
   * Builds version 1.2.0 and starts it through its launcher, to update to {@code version}: a zip
   * that holds only {@code version.txt}, as the rest of the app is carried over without a list.
   */
  private Process updateTo(String version) throws Exception {
    tool(
        "jpackage",
        "--type",
        "app-image",
        "--name",
        "Bücher App",
        "--dest",
        temp.toString(),
        "--input",
        input.toString(),
        "--main-jar",
        "examples.jar",
        "--main-class",
        App.class.getName(),
        "--runtime-image",
        runtime.toString());
    app = temp.resolve("Bücher App");
    Files.writeString(app.resolve("version.txt"), "1.2.0");
    updates = Files.createDirectories(temp.resolve("Bücher App updates"));
    Path zip = updates.resolve("download-" + version + ".zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry("version.txt"));
      out.write(version.getBytes(UTF_8));
    }
    String sha256 = HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
    Path launcher = app.resolve(WINDOWS ? "Bücher App.exe" : "bin/Bücher App");
    return new ProcessBuilder(launcher.toString(), zip.toString(), sha256)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  /** Asserts that no version is left beside the app's folder. */
  private void assertNothingBeside() throws IOException {
    try (Stream<Path> beside = Files.list(temp)) {
      assertEquals(List.of(), beside.filter(p -> p.toString().contains("App.")).toList());
    }
  }

  @SuppressWarnings("java:S2925") // polls files that other processes write, not a test's own thread
  private void waitFor(BooleanSupplier condition) throws InterruptedException {
    // Two minutes: on Windows a swap and its rollback each retry a locked rename for 30 s.
    for (long end = System.nanoTime() + 120_000_000_000L; !condition.getAsBoolean(); ) {
      assertTrue(
          System.nanoTime() < end,
          () -> "Timed out; the helper said: " + read(updates.resolve("helper.log")) + "; the app: "
              + read(temp.resolve("app.log")));
      Thread.sleep(100);
    }
  }

  private static void tool(String name, String... args) {
    assertEquals(0, ToolProvider.findFirst(name).orElseThrow().run(System.out, System.err, args));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException _) {
      return "nothing";
    }
  }

  /**
   * The app in the image: {@link ApplyUpdate}, with the update folder beside the app's, which logs
   * into {@code app.log} there too. Started with a download's file and checksum, it updates. A
   * version whose {@code version.txt} says {@value #BROKEN} exits at once.
   */
  public static final class App {

    static final String BROKEN = "broken";

    @SuppressWarnings("java:S2925") // the app, a process of its own: gives its child time to start
    public static void main(String[] args) throws Exception {
      Path app = Installation.launcher(Path.of("")).orElseThrow().app(); // for the folder only
      if (Files.readString(app.resolve("version.txt")).equals(BROKEN)) {
        // A process of its own that outlives it: the helper ends it too before it rolls back.
        Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Sleeper.class.getName())
            .start();
        Thread.sleep(1000);
        System.exit(1);
      }
      Path updates = app.resolveSibling(app.getFileName() + " updates");
      Download download = args.length == 2 ? new Download(Path.of(args[0]), args[1]) : null;
      try (PrintStream log = new PrintStream(
          new FileOutputStream(app.resolveSibling("app.log").toFile(), true), true, UTF_8)) {
        System.setOut(log);
        ApplyUpdate.start(updates, download);
      }
    }
  }

  /** A process that runs until it is ended. */
  public static final class Sleeper {

    @SuppressWarnings("java:S2925") // a process of its own, ended by the helper or after 10 minutes
    public static void main() throws InterruptedException {
      Thread.sleep(600_000);
    }
  }
}
