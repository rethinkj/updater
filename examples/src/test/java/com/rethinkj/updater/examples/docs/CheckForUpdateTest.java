package com.rethinkj.updater.examples.docs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.GitHubSource;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class CheckForUpdateTest {

  // The recording's newest archive is this stand-in: its size and digest there are this text's.
  private static final byte[] ARCHIVE =
      "stand-in for gh_2.104.0_linux_amd64.tar.gz".getBytes(UTF_8);

  @TempDir
  Path folder;

  private HttpServer server;
  private String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    // Four releases of cli/cli as GitHub answered, with their files on this server instead.
    server.createContext("/api/v3/repos/cli/cli/releases", exchange -> {
      try (InputStream in = getClass().getResourceAsStream("/github/releases.json")) {
        byte[] body = new String(in.readAllBytes(), UTF_8)
            .replace("https://github.com/", base)
            .getBytes(UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    server.createContext("/cli/cli/releases/download/v2.104.0/gh_2.104.0_linux_amd64.tar.gz", e -> {
      e.sendResponseHeaders(200, ARCHIVE.length);
      e.getResponseBody().write(ARCHIVE);
      e.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void printsTheNotesSinceTheInstalledVersionAndDownloadsTheNewest() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GitHubSource source = new GitHubSource(URI.create(base + "api/v3/"), "cli/cli", null);

    Path file =
        CheckForUpdate.check(source, folder, new PrintStream(out, true, UTF_8)).orElseThrow();

    assertArrayEquals(ARCHIVE, Files.readAllBytes(file));
    String notes = out.toString(UTF_8);
    assertTrue(notes.startsWith("# GitHub CLI 2.104.0"), notes);
    assertTrue(notes.contains("## Protect private forks"), notes);
    assertTrue(notes.contains("# GitHub CLI 2.103.2"), "the oldest newer than the installed one");
    assertFalse(notes.contains("# GitHub CLI 2.103.1"), "the installed version");
  }

  @Test
  @EnabledIfSystemProperty(named = "network.tests", matches = "true")
  void downloadsTheNewestFromGitHub() throws IOException {
    PrintStream nowhere = new PrintStream(OutputStream.nullOutputStream());
    Path file =
        CheckForUpdate.check(new GitHubSource("cli/cli"), folder, nowhere).orElseThrow();
    assertTrue(Files.size(file) > 0);
  }
}
