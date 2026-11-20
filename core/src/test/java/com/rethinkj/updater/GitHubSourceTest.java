package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class GitHubSourceTest {

  private HttpServer server;
  private URI api;
  private final Map<String, String> authorization = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
    // Two pages of recorded releases, linked in two Link fields with "next" in the second, given
    // as only a query.
    server.createContext("/api/v3/repos/cli/cli/releases", exchange -> {
      String page = exchange.getRequestURI().getQuery().contains("page=2") ? "2" : "1";
      authorization.put(
          page, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      if (page.equals("1")) {
        String pages = api + "/repos/cli/cli/releases?per_page=100&page=";
        exchange.getResponseHeaders().add("Link", "<" + pages + "9>; rel=\"last\"");
        exchange.getResponseHeaders().add("Link", "<?per_page=100&page=2>; rel=\"next\"");
      }
      try (InputStream in = getClass().getResourceAsStream("/github/releases-" + page + ".json")) {
        byte[] body = in.readAllBytes();
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    server.createContext("/api/v3/repos/busy/app/releases", exchange -> {
      exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
      exchange.getResponseHeaders().add("x-ratelimit-reset", "1900000000");
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
    });
    server.createContext("/api/v3/repos/busy/secondary/releases", exchange -> {
      byte[] body = "{\"message\":\"You have exceeded a secondary rate limit.\"}".getBytes();
      exchange.sendResponseHeaders(403, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.createContext("/api/v3/repos/busy/plain/releases", exchange -> {
      exchange.getResponseHeaders().add("Retry-After", "9223372036854775807");
      exchange.sendResponseHeaders(429, -1);
      exchange.close();
    });
    // A next page on another host: the token must not follow it there.
    server.createContext("/api/v3/repos/elsewhere/app/releases", exchange -> {
      String other = "http://localhost:" + server.getAddress().getPort();
      exchange.getResponseHeaders().add("Link", "<" + other + "/steal>; rel=\"next\"");
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write("[]".getBytes());
      exchange.close();
    });
    server.createContext("/api/v3/repos/loop/app/releases", exchange -> {
      exchange.getResponseHeaders().add("Link", "<#again>; rel=\"next\"");
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write("[]".getBytes());
      exchange.close();
    });
    server.createContext("/steal", exchange -> {
      authorization.put(
          "elsewhere", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write("[]".getBytes());
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void readsEveryPageAndLeavesOutDraftsAndOtherTags() throws IOException {
    List<Release> releases = new GitHubSource(api, "cli/cli", "test-token").releases();

    assertEquals(
        List.of("2.104.0", "2.103.3", "2.103.0-rc.1"),
        releases.stream().map(r -> r.version().toString()).toList());
    assertEquals("Bearer test-token", authorization.get("1"));
    assertEquals("Bearer test-token", authorization.get("2"));

    Release newest = releases.getFirst();
    assertEquals("v2.104.0", newest.tag());
    assertEquals("GitHub CLI 2.104.0", newest.title());
    assertEquals(Instant.parse("2026-11-12T19:03:51Z"), newest.published());
    assertTrue(newest.notes().startsWith("## Protect private forks"));
    assertFalse(newest.notes().contains("\r"));
    assertFalse(newest.preRelease());
    Asset checksums = newest.assets().getFirst();
    assertEquals("gh_2.104.0_checksums.txt", checksums.name());
    assertEquals(URI.create(api + "/repos/cli/cli/releases/assets/740718806"), checksums.url());
    assertEquals(1971, checksums.size());
    assertEquals(
        "180df77fb931591d5d65e33f02a76c9ade314d1c335569af73cc6c8fcb365d2c", checksums.sha256());

    Release candidate = releases.getLast();
    assertTrue(candidate.preRelease());
    assertEquals("v2.103.0-rc.1", candidate.title()); // no name: the tag
    assertEquals("", candidate.notes());
    assertEquals("", candidate.assets().getFirst().sha256()); // no digest reported

    // Without a token, files are downloaded from where a browser would.
    assertEquals(
        "https://github.com/cli/cli/releases/download/v2.104.0/gh_2.104.0_checksums.txt",
        new GitHubSource(api, "cli/cli", null)
                .releases()
                .getFirst()
                .assets()
                .getFirst()
                .url() + "");
  }

  @Test
  void reportsAUsedUpRateLimitWithItsEnd() {
    RateLimitException e = assertThrows(
        RateLimitException.class, () -> new GitHubSource(api, "busy/app", null).releases());
    assertEquals(Instant.ofEpochSecond(1900000000), e.retryAt().orElseThrow());
    for (String repository : List.of("busy/secondary", "busy/plain")) {
      assertThrows(
          RateLimitException.class,
          () -> new GitHubSource(api, repository, null).releases(),
          repository);
    }
  }

  @Test
  void readsOnlyOverHttpsOrFromThisMachine() {
    for (String address :
        List.of("http://ghe.example.com/api/v3", "http://127.example.com/", "ftp://localhost/")) {
      URI uri = URI.create(address);
      assertThrows(
          IllegalArgumentException.class, () -> new GitHubSource(uri, "a/b", null), address);
    }
    for (String address :
        List.of("http://LOCALHOST:8080/", "http://127.0.0.2/", "http://[0:0:0:0:0:0:0:1]/")) {
      new GitHubSource(URI.create(address), "a/b", "test-token");
    }
  }

  @Test
  void refusesATokenItCouldNotSendWithoutShowingIt() {
    // Such as a token read from a file saved with a byte order mark.
    IllegalArgumentException e = assertThrows(
        IllegalArgumentException.class, () -> new GitHubSource(api, "a/b", "\uFEFFtest-token"));
    assertFalse(e.getMessage().contains("test-token"), e.getMessage());
  }

  @Test
  void followsNoLinkToAnotherHostOrBack() {
    IOException e = assertThrows(
        IOException.class, () -> new GitHubSource(api, "elsewhere/app", "test-token").releases());
    assertTrue(e.getMessage().contains("outside"), e.getMessage());
    assertFalse(authorization.containsKey("elsewhere"));
    // Nor one back to a page already read, which would never end.
    assertThrows(IOException.class, () -> new GitHubSource(api, "loop/app", null).releases());
  }

  @Test
  @EnabledIfSystemProperty(named = "network.tests", matches = "true")
  void readsARealRepository() throws IOException {
    List<Release> releases = new GitHubSource("cli/cli").releases();
    assertTrue(releases.size() > 100, "more than one page");
    assertTrue(releases.stream()
        .flatMap(r -> r.assets().stream())
        .anyMatch(a -> a.sha256().length() == 64));
  }
}
