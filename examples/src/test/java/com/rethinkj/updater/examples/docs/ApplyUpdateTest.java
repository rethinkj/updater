package com.rethinkj.updater.examples.docs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rethinkj.updater.GitHubSource;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Version;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.json.JsonMapper;

/**
 * Updates an app image that jpackage built, with its own runtime, through its launcher: the app
 * finds the update on a GitHub that this test serves, downloads the new version's image, packed
 * as a release is, and the helper replaces the app with it, on Linux and Windows alike.
 */
class ApplyUpdateTest {

  private static final boolean WINDOWS = File.separatorChar == '\\';
  private static final String NAME = "Bücher App";
  private static final String ARCHIVE =
      "buecher-app-1.3.0-" + (WINDOWS ? "windows-x64.zip" : "linux-x64.tar.gz");

  @TempDir
  static Path shared;

  private static Path runtime;
  private static Path input;

  @TempDir
  Path temp;

  private HttpServer server;
  private final AtomicInteger listed = new AtomicInteger(); // requests for the releases
  private Path app;
  private Path updates;

  /** A runtime of the modules core needs, and the JARs the app ships with. */
  @BeforeAll
  static void prepare() throws Exception {
    runtime = shared.resolve("runtime");
    tool("jlink", "--add-modules", "java.base,java.net.http", "--output", runtime.toString());
    input = Files.createDirectories(shared.resolve("input"));
    tool(
        "jar",
        "--create",
        "--file",
        input.resolve("examples.jar").toString(),
        "-C",
        jar(ApplyUpdate.class).toString(),
        ".");
    for (Class<?> c :
        List.of(Installation.class, JsonMapper.class, JsonParser.class, JsonProperty.class)) {
      Files.copy(jar(c), input.resolve(jar(c).getFileName()));
    }
    var helper =
        (JarURLConnection) ClassLoader.getSystemResource("com/rethinkj/updater/helper/Helper.class")
            .openConnection();
    Files.copy(Path.of(helper.getJarFileURL().toURI()), input.resolve("helper.jar"));
  }

  @AfterEach
  void stop() throws InterruptedException {
    try {
      waitForTheApp();
    } finally {
      if (server != null) {
        server.stop(0);
      }
    }
  }

  @Test
  void findsAndAppliesTheUpdateAndKeepsTheNewVersionOnceItConfirms() throws Exception {
    Process old = updateTo("1.3.0");

    assertEquals(0, old.waitFor());
    Path result = updates.resolve("result.properties");
    waitFor(() -> Files.exists(result));
    Installation.Result reported =
        new Installation(app, updates, List.of()).result().orElseThrow();
    assertEquals(Installation.Result.Status.APPLIED, reported.status(), reported::message);
    assertEquals("1.3.0", Files.readString(app.resolve("version.txt")));
    waitForTheApp(); // and the helper, which deletes the old version once it has reported
    assertNothingBeside();
  }

  @Test
  void putsTheOldVersionBackAndStartsItIfTheNewOneExitsWithoutConfirming() throws Exception {
    Process old = updateTo(App.BROKEN);

    assertEquals(0, old.waitFor());
    Path log = temp.resolve("app.log");
    waitFor(() -> read(log).contains("ROLLED_BACK not-confirmed"));
    waitForTheApp();
    assertEquals(1, listed.get(), "the old version looked for the update again");
    assertEquals("1.2.0", Files.readString(app.resolve("version.txt")));
    assertNothingBeside();
    try (Stream<Path> downloads = Files.list(updates.resolve("downloads"))) {
      assertEquals(List.of(), downloads.toList());
    }
  }

  /**
   * Publishes release 1.3.0, whose image says {@code version} in {@code version.txt}, and starts
   * version 1.2.0 through its launcher, which updates to it.
   */
  private Process updateTo(String version) throws Exception {
    Path release = image(temp.resolve("release"), version);
    Path archive = temp.resolve(ARCHIVE);
    // As the page Package the app does it.
    if (WINDOWS) {
      run(
          Path.of(System.getenv("SystemRoot"), "System32", "tar.exe").toString(),
          "-a",
          "--options",
          "zip:hdrcharset=UTF-8",
          "-cf",
          archive.toString(),
          "-C",
          release.getParent().toString(),
          NAME);
    } else {
      run("tar", "-czf", archive.toString(), "-C", release.getParent().toString(), NAME);
    }
    serve(archive);

    app = image(temp, "1.2.0");
    updates = temp.resolve(NAME + " updates");
    Path launcher = app.resolve(WINDOWS ? NAME + ".exe" : "bin/" + NAME);
    return new ProcessBuilder(launcher.toString())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  /**
   * Builds the app's image in {@code dest}, with {@code version} in its {@code version.txt}, and
   * writes the list of its files into it, as the page Package the app does.
   */
  private static Path image(Path dest, String version) throws Exception {
    tool(
        "jpackage",
        "--type",
        "app-image",
        "--name",
        NAME,
        "--dest",
        dest.toString(),
        "--input",
        input.toString(),
        "--main-jar",
        "examples.jar",
        "--main-class",
        App.class.getName(),
        "--runtime-image",
        runtime.toString());
    Path image = dest.resolve(NAME);
    Files.writeString(image.resolve("version.txt"), version);
    Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
    run(java.toString(), "-jar", input.resolve("helper.jar").toString(), "list", image.toString());
    return image;
  }

  /**
   * Answers as GitHub does for the releases of {@code rethinkj/buecher-app}, with {@code archive}
   * and its checksum as the files of release 1.3.0, and tells the app where it runs.
   */
  private void serve(Path archive) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    byte[] bytes = Files.readAllBytes(archive);
    String sums = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        + "  " + ARCHIVE + "\n";
    server.createContext("/api/v3/repos/rethinkj/buecher-app/releases", exchange -> {
      listed.incrementAndGet();
      try (InputStream in = getClass().getResourceAsStream("/github/app-releases.json")) {
        send(
            exchange,
            new String(in.readAllBytes(), UTF_8)
                .replace("https://github.com/", base)
                .getBytes(UTF_8));
      }
    });
    String files = "/rethinkj/buecher-app/releases/download/v1.3.0/";
    server.createContext(files, exchange -> {
      String name = exchange.getRequestURI().getPath().substring(files.length());
      if (name.equals("SHA256SUMS")) {
        send(exchange, sums.getBytes(UTF_8));
      } else if (name.equals(ARCHIVE)) {
        send(exchange, bytes);
      } else {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      }
    });
    server.start();
    Files.writeString(temp.resolve("api.txt"), base + "api/v3/");
  }

  private static void send(HttpExchange exchange, byte[] body) throws IOException {
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  /** Waits until no process runs from the temporary folder, which Windows can delete only then. */
  private void waitForTheApp() throws InterruptedException {
    waitFor(() -> ProcessHandle.allProcesses()
        .noneMatch(
            p -> p.info().command().filter(c -> c.startsWith(temp.toString())).isPresent()));
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

  private static Path jar(Class<?> c) throws Exception {
    return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static void tool(String name, String... args) {
    assertEquals(0, ToolProvider.findFirst(name).orElseThrow().run(System.out, System.err, args));
  }

  private static void run(String... command) throws Exception {
    assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor(), command[0]);
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException _) {
      return "nothing";
    }
  }

  /**
   * The app in the image: {@link ApplyUpdate}, with the update folder beside the app's, and the
   * GitHub API named in {@code api.txt} there. It logs into {@code app.log} there too. A version
   * whose {@code version.txt} says {@value #BROKEN} exits at once.
   */
  public static final class App {

    static final String BROKEN = "broken";

    @SuppressWarnings("java:S2925") // the app, a process of its own: gives its child time to start
    public static void main(String[] args) throws Exception {
      Path app = Installation.launcher(Path.of("")).orElseThrow().app(); // for the folder only
      String version = Files.readString(app.resolve("version.txt"));
      if (version.equals(BROKEN)) {
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
      // Not closed, so that an exception this ends with is logged as well.
      PrintStream log = new PrintStream(
          new FileOutputStream(app.resolveSibling("app.log").toFile(), true), true, UTF_8);
      System.setOut(log);
      System.setErr(log);
      URI api = URI.create(Files.readString(app.resolveSibling("api.txt")));
      ApplyUpdate.start(
          app.resolveSibling(app.getFileName() + " updates"),
          new GitHubSource(api, "rethinkj/buecher-app", null),
          Version.parse(version));
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
