package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    server.createContext("/app.zip", exchange -> {
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
    // Sends a first part, then nothing until the test ends.
    server.createContext("/slow.zip", exchange -> {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write(new byte[1000]);
      exchange.getResponseBody().flush();
      try {
        release.await();
      } catch (InterruptedException e) {
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
    assertThrows(IllegalArgumentException.class, () -> r.asset(Pattern.compile("app-.*")));
  }

  @Test
  void verifiesWithTheChecksumTheSourceReports() throws IOException {
    Asset app = asset("app.zip", sha256(APP));
    List<Long> progress = new ArrayList<>();

    Path file = new Downloader().download(release(app), app, folder, progress::add);

    assertArrayEquals(APP, Files.readAllBytes(file));
    assertEquals(folder, file.getParent());
    assertEquals((long) APP.length, progress.getLast());
  }

  @Test
  void verifiesWithAChecksumFileOfTheRelease() throws IOException {
    Asset app = asset("app.zip", "");
    Path file =
        new Downloader().download(release(app, asset("SHA256SUMS", "")), app, folder, n -> {});
    assertArrayEquals(APP, Files.readAllBytes(file));
  }

  @Test
  void handsOnNothingWithoutAMatchingChecksum() throws IOException {
    Downloader downloader = new Downloader();
    Asset wrong = asset("app.zip", "1".repeat(64));
    Asset unknown = asset("app.zip", "");
    Asset plain = new Asset("app.zip", URI.create("http://example.com/app.zip"), 1, sha256(APP));
    for (Asset a : List.of(wrong, unknown, plain)) {
      assertThrows(IOException.class, () -> downloader.download(release(a), a, folder, n -> {}));
    }
    try (var left = Files.list(folder)) {
      assertEquals(List.of(), left.toList());
    }
  }

  @Test
  void stopsAndCleansUpWhenInterrupted() throws IOException {
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
    try (var left = Files.list(folder)) {
      assertEquals(List.of(), left.toList());
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "network.tests", matches = "true")
  void downloadsFromGitHub() throws IOException {
    Release latest = Update.find(Version.parse("0.0.0"), new GitHubSource("cli/cli").releases())
        .orElseThrow()
        .latest();
    Asset sums = latest.asset(Pattern.compile(".*_checksums\\.txt")).orElseThrow();
    Path file = new Downloader().download(latest, sums, folder, n -> {});
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
