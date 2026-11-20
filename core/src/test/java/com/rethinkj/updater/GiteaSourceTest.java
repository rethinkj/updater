package com.rethinkj.updater;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GiteaSourceTest {

  @TempDir
  Path folder;

  private HttpServer server;
  private String base;
  private final Map<String, String> authorization = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    // Two pages each of gitea/runner on gitea.com (Gitea) and forgejo/forgejo on codeberg.org
    // (Forgejo, here below a path), linked as both link them, with their files on this server.
    releases("/api/v1/repos/gitea/runner/releases", "gitea");
    releases("/forgejo/api/v1/repos/forgejo/forgejo/releases", "forgejo");
    server.start();
  }

  private void releases(String path, String recording) {
    server.createContext(path, exchange -> {
      String page = exchange.getRequestURI().getQuery().contains("page=2") ? "2" : "1";
      authorization.put(
          recording + page, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      if (page.equals("1")) {
        String pages = base + path.substring(1) + "?limit=50&page=";
        exchange
            .getResponseHeaders()
            .add("Link", "<" + pages + "2>; rel=\"next\",<" + pages + "2>; rel=\"last\"");
      }
      try (InputStream in =
          getClass().getResourceAsStream("/" + recording + "/releases-" + page + ".json")) {
        byte[] body = new String(in.readAllBytes(), UTF_8)
            .replace("https://gitea.com/", base)
            .replace("https://codeberg.org/", base + "forgejo/")
            .getBytes(UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void readsEveryPageOfGiteaAndForgejo() throws IOException {
    List<Release> gitea =
        new GiteaSource(URI.create(base), "gitea/runner", "test-token").releases();

    assertEquals(
        List.of("6.0.0", "0.1.0", "0.0.1"),
        gitea.stream().map(r -> r.version().toString()).toList());
    assertEquals("Bearer test-token", authorization.get("gitea1"));
    assertEquals("Bearer test-token", authorization.get("gitea2"));
    Release newest = gitea.getFirst();
    assertEquals("v6.0.0", newest.tag());
    assertEquals(Instant.parse("2026-11-11T10:50:12Z"), newest.published());
    assertTrue(newest.notes().startsWith("## ⚠️ Breaking changes"), newest.notes());
    assertFalse(newest.preRelease());
    Asset checksums = newest.assets().getFirst();
    assertEquals("checksums.txt", checksums.name());
    // Where a browser downloads it, also with a token: the instance takes the token there.
    assertEquals(
        URI.create(base + "gitea/runner/releases/download/v6.0.0/checksums.txt"), checksums.url());
    assertEquals(2601, checksums.size());
    assertEquals("", checksums.sha256()); // Gitea reports none
    // Marked as a pre-release, though its version is not one.
    assertTrue(gitea.get(1).preRelease());
    assertFalse(gitea.get(1).version().isPreRelease());

    // The instance's address given without the slash at its end.
    List<Release> forgejo =
        new GiteaSource(URI.create(base + "forgejo"), "forgejo/forgejo", null).releases();

    assertEquals(
        List.of("17.0.2", "1.18.0-rc1"),
        forgejo.stream().map(r -> r.version().toString()).toList());
    assertEquals("null", authorization.get("forgejo2"));
    Release latest = forgejo.getFirst();
    assertEquals(Instant.parse("2026-11-18T18:33:02Z"), latest.published()); // sent as +01:00
    assertTrue(latest.notes().endsWith("release-notes-published/17.0.2.md"), latest.notes());
    assertEquals(
        List.of("forgejo-17.0.2-linux-amd64", "forgejo-17.0.2-linux-amd64.sha256"),
        latest.assets().stream().map(Asset::name).toList());
    assertEquals(122786344, latest.assets().getFirst().size());
    assertTrue(forgejo.getLast().preRelease());
  }

  @Test
  void downloadsAPrivateFileSendingTheTokenOnlyToTheInstance() throws IOException {
    byte[] runner = "stand-in for gitea-runner-6.0.0-linux-amd64".getBytes(UTF_8);
    String storage = base.replace("127.0.0.1", "localhost"); // another host
    // As Gitea does for a private repository: nothing without the token, and a file it keeps in
    // object storage as a redirect there.
    server.createContext("/gitea/runner/releases/download/v6.0.0/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        exchange.sendResponseHeaders(404, -1);
      } else if (path.endsWith("/checksums.txt")) {
        byte[] body = (sha256(runner) + "  gitea-runner-6.0.0-linux-amd64\n").getBytes(UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } else {
        exchange.getResponseHeaders().add("Location", storage + "storage/runner");
        exchange.sendResponseHeaders(302, -1);
      }
      exchange.close();
    });
    server.createContext("/storage/runner", exchange -> {
      authorization.put(
          "storage", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      exchange.sendResponseHeaders(200, runner.length);
      exchange.getResponseBody().write(runner);
      exchange.close();
    });
    GiteaSource source = new GiteaSource(URI.create(base), "gitea/runner", "test-token");
    Release latest = source.releases().getFirst();
    Asset asset = latest.asset(Pattern.compile("gitea-runner-.*-linux-amd64")).orElseThrow();

    // Verified with the release's checksums.txt, which the instance sends only with the token.
    Path file = new Downloader(source).download(latest, asset, folder, n -> {}).file();

    assertArrayEquals(runner, Files.readAllBytes(file));
    assertEquals("null", authorization.get("storage"));
    // An instance below a path gets the token there, and another app on the same host does not.
    GiteaSource below =
        new GiteaSource(URI.create(base + "forgejo"), "forgejo/forgejo", "test-token");
    assertEquals(
        "Bearer test-token",
        below.authorization(URI.create(base + "forgejo/forgejo/forgejo/releases/download/v1/a")));
    assertNull(below.authorization(URI.create(base + "storage/runner")));
    assertNull(below.authorization(URI.create(base + "forgejo/../storage/runner")));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
