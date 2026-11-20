package com.rethinkj.updater;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The releases of a repository on GitHub, or on GitHub Enterprise Server, read through the REST
 * API.
 *
 * <p>Without a token, GitHub allows 60 requests an hour per IP address, and each request reads up
 * to 100 releases. A token raises the limit and lets a private repository be read. With a token,
 * an asset's {@link Asset#url() url} is its address in the API, from which a {@link Downloader}
 * given this source downloads it with the token.
 */
public final class GitHubSource implements Source {

  private static final URI GITHUB_API = URI.create("https://api.github.com/");
  private static final String HTTPS = "https";
  private static final Pattern OWNER_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
  private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI api;
  private final String repository;
  private final String token;
  private final boolean github;
  private final String service;
  private final String scope;
  private final HttpClient http;

  /**
   * A public repository on github.com.
   *
   * @param repository owner and name, such as {@code rethinkj/updater}
   */
  public GitHubSource(String repository) {
    this(GITHUB_API, repository, null);
  }

  /**
   * A repository on github.com or on GitHub Enterprise Server.
   *
   * @param api the REST API, {@code https://api.github.com/} or {@code https://<host>/api/v3/}
   * @param repository owner and name, such as {@code rethinkj/updater}
   * @param token a token that may read the repository's releases, or {@code null} for none
   * @throws IllegalArgumentException if {@code repository} is not owner/name, {@code api} is
   *     neither https nor on this machine, or {@code token} has characters other than visible ASCII
   */
  public GitHubSource(URI api, String repository, String token) {
    this(api, repository, token, null);
  }

  /**
   * A repository on GitHub, or, given {@code instance}, on a service whose API copies GitHub's, as
   * Gitea's and Forgejo's do ({@link GiteaSource}): named by its host in messages, its files
   * downloaded from where a browser would, also with a token, and the token sent only below the
   * path {@code instance}, which ends in a slash.
   */
  GitHubSource(URI api, String repository, String token, String instance) {
    if (!OWNER_NAME.matcher(repository).matches()) {
      throw new IllegalArgumentException("Not a repository as owner/name: \"" + repository + "\"");
    }
    String base = api.toString();
    this.api = URI.create(base.endsWith("/") ? base : base + "/");
    this.repository = repository;
    this.token = token == null || token.isBlank() ? null : token.strip();
    // The HTTP client would refuse such a header and name its value, the token, in the exception.
    if (this.token != null && !this.token.chars().allMatch(c -> c > ' ' && c < 0x7f)) {
      throw new IllegalArgumentException("The token has characters other than visible ASCII");
    }
    // Over plain http anyone on the way could swap a release's addresses and checksums.
    if (this.api.getHost() == null
        || !HTTPS.equalsIgnoreCase(this.api.getScheme())
            && !("http".equalsIgnoreCase(this.api.getScheme()) && loopback(this.api))) {
      throw new IllegalArgumentException(
          "Releases are read only over https or from this machine, not from " + this.api);
    }
    this.github = instance == null;
    this.service = github ? "GitHub" : this.api.getHost();
    this.scope = github ? "" : instance;
    // Redirects are followed by hand, so that the token goes only to the API's own origin.
    this.http = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public List<Release> releases() throws IOException {
    List<Release> releases = new ArrayList<>();
    URI next =
        api.resolve("repos/" + repository + "/releases?" + (github ? "per_page=100" : "limit=50"));
    Set<String> seen = new HashSet<>();
    while (next != null) {
      if (!seen.add(next.toString().replaceFirst("#.*", ""))) {
        throw new IOException(service + " links back to a page it already sent: " + next);
      }
      HttpResponse<byte[]> response = get(next);
      JsonNode page;
      try {
        page = JSON.readTree(response.body());
      } catch (JacksonException e) {
        throw new IOException(service + "'s answer from " + next + " is not valid JSON", e);
      }
      if (!page.isArray()) {
        throw new IOException(service + " did not answer with a list of releases from " + next);
      }
      for (JsonNode node : page) {
        release(node).ifPresent(releases::add);
      }
      // Several Link fields are one list together (RFC 8288).
      Matcher link = NEXT_LINK.matcher(String.join(",", response.headers().allValues("link")));
      next = link.find() ? follow(response.uri(), link.group(1)) : null;
    }
    return releases;
  }

  private HttpResponse<byte[]> get(URI start) throws IOException {
    URI uri = start;
    for (int redirects = 0; ; redirects++) {
      HttpResponse<byte[]> response = send(uri);
      int status = response.statusCode();
      if (status == 200) {
        return response;
      }
      Optional<String> location = response.headers().firstValue("location");
      if (status / 100 == 3 && location.isPresent() && redirects < 5) {
        uri = follow(uri, location.get()); // such as a renamed repository
        continue;
      }
      throw failure(response);
    }
  }

  private HttpResponse<byte[]> send(URI uri) throws IOException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(TIMEOUT)
        .header("Accept", github ? "application/vnd.github+json" : "application/json")
        .header("User-Agent", "rethinkj-updater");
    if (github) {
      request.header("X-GitHub-Api-Version", "2022-11-28");
    }
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    // The request's timeout ends when the headers arrive; this one bounds the body as well.
    CompletableFuture<HttpResponse<byte[]>> answer =
        http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    try {
      return answer.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      answer.cancel(true);
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while asking " + service + " for " + uri);
    } catch (TimeoutException _) {
      answer.cancel(true);
      throw new HttpTimeoutException(
          service + " did not answer " + uri + " within " + TIMEOUT.toSeconds() + " seconds");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw new IOException(
          "Could not ask " + service + " for " + uri + ": " + cause.getMessage(), cause);
    }
  }

  private IOException failure(HttpResponse<byte[]> response) {
    int status = response.statusCode();
    var headers = response.headers();
    Optional<String> retryAfter = headers.firstValue("retry-after");
    // A secondary rate limit may leave quota and send no header; only its message says so.
    boolean limited = status == 429
        || status == 403
            && (headers.firstValue("x-ratelimit-remaining").filter("0"::equals).isPresent()
                || retryAfter.isPresent()
                || new String(response.body(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .contains("rate limit"));
    if (limited) {
      Instant retryAt;
      try {
        retryAt = retryAfter
            .flatMap(GitHubSource::number)
            .map(Instant.now()::plusSeconds)
            .or(() -> headers
                .firstValue("x-ratelimit-reset")
                .flatMap(GitHubSource::number)
                .map(Instant::ofEpochSecond))
            .orElse(null);
      } catch (ArithmeticException | DateTimeException _) {
        retryAt = null; // a time no clock reaches says nothing
      }
      return new RateLimitException(
          service + "'s rate limit is used up"
              + (github && token == null ? "; a token raises it" : "")
              + (retryAt == null ? "" : ", until " + retryAt),
          retryAt);
    }
    return new IOException(
        switch (status) {
          case 401 -> service + " did not accept the token";
          case 404 ->
            service + " has no repository " + repository
                + (token == null ? ", or it is private" : " that the token may read");
          default -> service + " answered " + status + " to " + response.uri();
        });
  }

  /** Where a Link or Location header points, if the token may go there. */
  private URI follow(URI base, String reference) throws IOException {
    URI uri;
    try {
      // URI.resolve drops the path for a reference of only a query, which RFC 3986 keeps.
      uri = reference.startsWith("?")
          ? URI.create(base.toString().replaceFirst("[?#].*", "") + reference)
          : base.resolve(reference);
    } catch (IllegalArgumentException e) {
      throw new IOException(service + " pointed to an invalid address: " + reference, e);
    }
    return sameOrigin(uri);
  }

  /** The address, if the token may go there: it must not reach any other. */
  private URI sameOrigin(URI uri) throws IOException {
    if (!onApi(uri)) {
      throw new IOException(service + " pointed to " + uri + ", outside " + api);
    }
    return uri;
  }

  /**
   * The Authorization header for a request to {@code uri}, or {@code null} if there is no token
   * or the address is not on the API's origin, or not below the instance's path.
   */
  String authorization(URI uri) {
    return token != null && onApi(uri) ? "Bearer " + token : null;
  }

  private boolean onApi(URI uri) {
    return api.getScheme().equalsIgnoreCase(String.valueOf(uri.getScheme()))
        && api.getHost().equalsIgnoreCase(String.valueOf(uri.getHost()))
        && port(api) == port(uri)
        // As sent and with dot segments resolved: another app on the same host must not get it.
        && uri.getRawPath().startsWith(scope)
        && uri.normalize().getRawPath().startsWith(scope)
        && (github || !escapedSeparator(uri.getRawPath()));
  }

  /**
   * Whether the path escapes a dot, slash or backslash, which a proxy may decode into a step up, or
   * a percent sign, which may escape one of them once more.
   */
  private static boolean escapedSeparator(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.contains("%2e")
        || lower.contains("%2f")
        || lower.contains("%5c")
        || lower.contains("%25");
  }

  /** Whether the host is this machine: {@code localhost} or a loopback IP address, never a name. */
  static boolean loopback(URI uri) {
    String host = String.valueOf(uri.getHost());
    if (host.equalsIgnoreCase("localhost")) {
      return true;
    }
    try {
      // Parses an address without asking DNS, and refuses a name such as 127.example.com.
      return InetAddress.ofLiteral(host.replaceFirst("^\\[(.*)]$", "$1")).isLoopbackAddress();
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static int port(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return HTTPS.equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private Optional<Release> release(JsonNode node) throws IOException {
    if (node.path("draft").asBoolean(false)) {
      return Optional.empty(); // only visible with a token, and not published
    }
    String tag = text(node, "tag_name");
    Version version;
    try {
      version = Version.parse(tag);
    } catch (IllegalArgumentException _) {
      return Optional.empty(); // such as "nightly": not a version to update to
    }
    List<Asset> assets = new ArrayList<>();
    for (JsonNode a : node.path("assets")) {
      String digest = text(a, "digest");
      long id = a.path("id").asLong(0);
      assets.add(new Asset(
          text(a, "name"),
          // GitHub's API serves the files of a private repository as well, and only with the token.
          github && token != null && id > 0
              ? api.resolve("repos/" + repository + "/releases/assets/" + id)
              : uri(a, "browser_download_url"),
          a.path("size").asLong(0),
          digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : ""));
    }
    String title = text(node, "name").strip();
    try {
      return Optional.of(new Release(
          version,
          tag,
          title.isEmpty() ? tag : title,
          Instant.parse(text(node, "published_at")),
          text(node, "body").replace("\r\n", "\n").replace('\r', '\n'),
          node.path("prerelease").asBoolean(false),
          uri(node, "html_url"),
          assets));
    } catch (DateTimeParseException e) {
      throw new IOException(
          service + " sent release " + tag + " without a valid publication date", e);
    }
  }

  private static Optional<Long> number(String header) {
    try {
      long n = Long.parseLong(header.strip());
      return n < 0 ? Optional.empty() : Optional.of(n);
    } catch (NumberFormatException _) {
      return Optional.empty(); // Retry-After may also be a date, which GitHub does not send
    }
  }

  /** The field as text, empty if it is missing or null. */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.stringValue() : "";
  }

  /** The field as an absolute HTTP(S) address. */
  private URI uri(JsonNode node, String field) throws IOException {
    String text = text(node, field);
    try {
      URI uri = new URI(text);
      String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
      if ((scheme.equals(HTTPS) || scheme.equals("http")) && uri.getHost() != null) {
        return uri;
      }
    } catch (URISyntaxException _) {
      // reported below
    }
    throw new IOException(service + " sent no valid " + field + ": \"" + text + "\"");
  }
}
