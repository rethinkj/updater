package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class DownloaderTest {

  private static final byte[] APP = "the app, version 1.1.0".getBytes(StandardCharsets.UTF_8);

  @TempDir
  Path folder;

  private HttpServer server;
  private String base;
  private final CountDownLatch release = new CountDownLatch(1);
  private final List<String> tokens = new CopyOnWriteArrayList<>(); // as /app.zip received them

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    server.createContext("/app.zip", exchange -> {
      tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      exchange.sendResponseHeaders(200, APP.length);
      exchange.getResponseBody().write(APP);
      exchange.close();
    });
    server.createContext("/SHA256SUMS", exchange -> {
      byte[] body = ("0".repeat(64) + "  other.zip\r\n" + sha256(APP) + " *app.zip\n").getBytes();
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    // A redirect from this machine, which could lead anywhere over plain http.
    server.createContext("/away.zip", exchange -> {
      exchange.getResponseHeaders().add("Location", base + "app.zip");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    // Sends a first part, then nothing until the test ends.
    server.createContext("/slow.zip", exchange -> {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write(new byte[1000]);
      exchange.getResponseBody().flush();
      try {
        release.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
  }

  @Test
  void picksTheOneAssetWhoseNameMatches() {
    Release r =
        release(asset("app-1.1.0-linux-x64.tar.gz", ""), asset("app-1.1.0-windows-x64.zip", ""));
    assertEquals(
        "app-1.1.0-windows-x64.zip",
        r.asset(Pattern.compile("app-.*-windows-x64\\.zip")).orElseThrow().name());
    assertTrue(r.asset(Pattern.compile("app-.*-mac.*")).isEmpty());
    Pattern both = Pattern.compile("app-.*");
    assertThrows(IllegalArgumentException.class, () -> r.asset(both));
  }

  @Test
  void verifiesWithTheChecksumTheSourceReports() throws IOException {
    Asset app = asset("app.zip", sha256(APP));
    List<Long> progress = new ArrayList<>();

    Path file =
        new Downloader().download(release(app), app, folder, progress::add).file();

    assertArrayEquals(APP, Files.readAllBytes(file));
    assertEquals(folder, file.getParent());
    assertEquals((long) APP.length, progress.getLast());
  }

  @Test
  void verifiesWithAChecksumFileOfTheRelease() throws IOException {
    Asset app = asset("app.zip", "");
    Download download =
        new Downloader().download(release(app, asset("SHA256SUMS", "")), app, folder, n -> {});
    assertArrayEquals(APP, Files.readAllBytes(download.file()));
    assertEquals(sha256(APP), download.sha256()); // for the helper, which checks it again
  }

  @Test
  void handsOnNothingWithoutAMatchingChecksum() {
    Downloader downloader = new Downloader();
    Asset app = asset("app.zip", sha256(APP));
    Asset unknown = asset("app.zip", "");
    Asset wrong = asset("app.zip", "1".repeat(64));
    Asset plain = new Asset("app.zip", URI.create("http://example.com/app.zip"), 1, sha256(APP));
    Asset hostless = new Asset("app.zip", URI.create("https:/app.zip"), 1, sha256(APP));
    Asset away = asset("away.zip", sha256(APP));
    Asset alteredSums = asset("SHA256SUMS", "2".repeat(64)); // the source says otherwise
    for (Release r : List.of(
        release(wrong),
        release(unknown),
        release(plain),
        release(hostless),
        release(away),
        release(unknown, alteredSums))) {
      Asset a = r.assets().getFirst();
      assertThrows(
          IOException.class, () -> downloader.download(r, a, folder, n -> {}), a.url() + "");
    }
    // A callback that fails leaves nothing behind either.
    Release withApp = release(app);
    assertThrows(
        AssertionError.class,
        () -> downloader.download(withApp, app, folder, n -> {
          throw new AssertionError("fails");
        }));
    assertEquals(List.of(), files());
  }

  @Test
  void stopsAndCleansUpWhenInterrupted() {
    Asset slow = asset("slow.zip", sha256(APP));
    try {
      assertThrows(
          InterruptedIOException.class,
          () -> new Downloader()
              .download(release(slow), slow, folder, n -> Thread.currentThread().interrupt()));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(List.of(), files());
  }

  @Test
  void downloadsAPrivateFileSendingTheTokenOnlyToTheApi() throws IOException {
    String cdn = base.replace("127.0.0.1", "localhost"); // another host
    // As GitHub does for a private repository: nothing without the token, and a file only to a
    // request for its content, as a redirect to its CDN.
    server.createContext("/api/v3/repos/owner/app/releases", exchange -> {
      var headers = exchange.getRequestHeaders();
      String path = exchange.getRequestURI().getPath();
      if (!"Bearer test-token".equals(headers.getFirst("Authorization"))) {
        exchange.sendResponseHeaders(404, -1);
      } else if (path.endsWith("/releases")) {
        try (InputStream in = getClass().getResourceAsStream("/github/private-releases.json")) {
          byte[] body = in.readAllBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
      } else if ("application/octet-stream".equals(headers.getFirst("Accept"))) {
        String file = path.endsWith("/assets/11") ? "app.zip" : "SHA256SUMS";
        exchange.getResponseHeaders().add("Location", cdn + file);
        exchange.sendResponseHeaders(302, -1);
      } else {
        exchange.sendResponseHeaders(415, -1);
      }
      exchange.close();
    });
    GitHubSource source = new GitHubSource(URI.create(base + "api/v3"), "owner/app", "test-token");
    Release latest = source.releases().getFirst();
    Asset app = latest.asset(Pattern.compile("app\\.zip")).orElseThrow();
    Downloader downloader = new Downloader(source);

    // Verified with the release's checksum file, which comes through the API as well.
    Path file = downloader.download(latest, app, folder, n -> {}).file();
    assertArrayEquals(APP, Files.readAllBytes(file));
    // A file on another host gets no token either.
    Asset elsewhere = new Asset("app.zip", URI.create(cdn + "app.zip"), 0, sha256(APP));
    downloader.download(release(elsewhere), elsewhere, folder, n -> {});
    assertEquals(List.of("null", "null"), tokens);
  }

  private List<Path> files() {
    try (var files = Files.list(folder)) {
      return files.toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "network.tests", matches = "true")
  void downloadsFromGitHub() throws IOException {
    Release latest = Update.find(Version.parse("0.0.0"), new GitHubSource("cli/cli").releases())
        .orElseThrow()
        .latest();
    Asset sums = latest.asset(Pattern.compile(".*_checksums\\.txt")).orElseThrow();
    Path file = new Downloader().download(latest, sums, folder, n -> {}).file();
    assertEquals(sums.size(), Files.size(file));
  }

  private Asset asset(String name, String sha256) {
    return new Asset(name, URI.create(base + name), 0, sha256);
  }

  private static Release release(Asset... assets) {
    return new Release(
        Version.parse("1.1.0"),
        "v1.1.0",
        "1.1.0",
        Instant.EPOCH,
        "",
        false,
        URI.create("https://example.com/releases/v1.1.0"),
        List.of(assets));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
