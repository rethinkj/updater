package com.rethinkj.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the files of a release and hands on only those that match their SHA-256 checksum.
 *
 * <p>The checksum is the one the source reports ({@link Asset#sha256()}), or else the one a
 * checksum file attached to the same release lists: {@code SHA256SUMS}, {@code SHA256SUMS.txt} or a
 * file whose name ends in {@code checksums.txt}, with lines of the form {@code <hex>  <file name>}.
 * A file whose checksum is not known is not downloaded.
 *
 * <p>Files are downloaded over https, or from this machine. To cancel a download, interrupt the
 * thread that runs it, for instance with {@link java.util.concurrent.Future#cancel(boolean)
 * Future.cancel(true)}. A download that sends nothing for 30 seconds fails.
 */
public final class Downloader {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final long MAX_CHECKSUM_FILE = 1 << 20;
  private static final Pattern CHECKSUM_FILE =
      Pattern.compile("(?i)sha256sums(?:\\.txt)?|.*checksums\\.txt");
  // As sha256sum writes it: the checksum, a space, and a space or "*" before the file name.
  private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-fA-F]{64}) [ *](.+)");

  // Redirects go only from https to https; one from this machine could lead anywhere over http.
  private final HttpClient https = client(HttpClient.Redirect.NORMAL);
  private final HttpClient local = client(HttpClient.Redirect.NEVER);

  /**
   * Downloads {@code asset} of {@code release} into a new file in {@code folder} and verifies it.
   *
   * @param progress told the number of bytes received so far, each time more arrive; the total is
   *     {@link Asset#size()}
   * @return the verified file; the caller deletes it once it is no longer needed
   * @throws InterruptedIOException if the thread was interrupted, which leaves it interrupted
   * @throws IOException if no checksum is known for the file, it does not match, or the download
   *     fails. No file is left behind.
   */
  public Path download(Release release, Asset asset, Path folder, LongConsumer progress)
      throws IOException {
    String expected = checksum(release, asset);
    // The name comes from the server: keep only what is safe in a file name on every platform.
    Path file = Files.createTempFile(
        folder, "download-", "-" + asset.name().replaceAll("[^A-Za-z0-9._-]", "_"));
    try {
      MessageDigest digest = newDigest();
      try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest)) {
        copy(asset, out, asset.size() > 0 ? asset.size() : Long.MAX_VALUE, progress);
      }
      String actual = HexFormat.of().formatHex(digest.digest());
      if (!actual.equals(expected)) {
        throw new IOException(asset.name() + " does not match its checksum: expected " + expected
            + ", received " + actual);
      }
      return file;
    } catch (Throwable e) { // also an Error, say from progress: the path never reaches the caller
      try {
        Files.deleteIfExists(file);
      } catch (IOException d) {
        e.addSuppressed(d);
      }
      throw e;
    }
  }

  /** The checksum the source reports, or the one in a checksum file of the release. */
  private String checksum(Release release, Asset asset) throws IOException {
    if (!asset.sha256().isEmpty()) {
      return asset.sha256();
    }
    for (Asset sums : release.assets()) {
      if (sums.equals(asset) || !CHECKSUM_FILE.matcher(sums.name()).matches()) {
        continue;
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      copy(sums, out, MAX_CHECKSUM_FILE, n -> {});
      String reported = sums.sha256();
      if (!reported.isEmpty() && !reported.equals(sha256(out.toByteArray()))) {
        throw new IOException(
            sums.name() + " of " + release.tag() + " does not match its checksum");
      }
      for (String line : out.toString(StandardCharsets.UTF_8).lines().toList()) {
        Matcher m = CHECKSUM_LINE.matcher(line.strip());
        if (m.matches() && m.group(2).equals(asset.name())) {
          return m.group(1).toLowerCase(Locale.ROOT);
        }
      }
    }
    throw new IOException("No SHA-256 checksum is known for " + asset.name() + " of "
        + release.tag() + ", so it is not downloaded");
  }

  private void copy(Asset asset, OutputStream out, long max, LongConsumer progress)
      throws IOException {
    URI uri = asset.url();
    boolean secure = "https".equalsIgnoreCase(uri.getScheme());
    if (!secure && !("http".equalsIgnoreCase(uri.getScheme()) && GitHubSource.loopback(uri))) {
      throw new IOException("Files are downloaded only over https, not from " + uri);
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(TIMEOUT)
        .header("User-Agent", "rethinkj-updater")
        .build();
    HttpResponse<Flow.Publisher<List<ByteBuffer>>> response;
    try {
      response = (secure ? https : local).send(request, HttpResponse.BodyHandlers.ofPublisher());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while downloading " + uri);
    }
    Parts body = new Parts();
    response.body().subscribe(body);
    try {
      if (response.statusCode() != 200) {
        throw new IOException("Downloading " + uri + " answered " + response.statusCode());
      }
      long received = 0;
      for (List<ByteBuffer> part; (part = body.next(uri)) != null; ) {
        for (ByteBuffer buffer : part) {
          received += buffer.remaining();
          if (received > max) {
            throw new IOException(uri + " is larger than the " + max + " bytes expected");
          }
          byte[] bytes = new byte[buffer.remaining()];
          buffer.get(bytes);
          out.write(bytes);
        }
        progress.accept(received);
      }
    } finally {
      body.cancel(); // frees the connection if the body was not read to its end
    }
  }

  private static HttpClient client(HttpClient.Redirect redirect) {
    return HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(redirect)
        .build();
  }

  private static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(newDigest().digest(bytes));
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime has SHA-256", e);
    }
  }

  /** The body as it arrives, waiting at most {@link #TIMEOUT} for each part. */
  private static final class Parts implements Flow.Subscriber<List<ByteBuffer>> {

    private static final Object END = new Object();
    private final BlockingQueue<Object> parts = new LinkedBlockingQueue<>();
    private volatile Flow.Subscription subscription;
    private volatile boolean cancelled;

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      if (cancelled) {
        s.cancel();
      } else {
        s.request(1);
      }
    }

    @Override
    public void onNext(List<ByteBuffer> part) {
      parts.add(part);
    }

    @Override
    public void onError(Throwable error) {
      parts.add(error);
    }

    @Override
    public void onComplete() {
      parts.add(END);
    }

    /** The next part, or {@code null} at the end of the body. */
    List<ByteBuffer> next(URI uri) throws IOException {
      Object part;
      try {
        part = parts.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Interrupted while downloading " + uri);
      }
      if (part == null) {
        throw new HttpTimeoutException(
            uri + " sent nothing for " + TIMEOUT.toSeconds() + " seconds");
      }
      if (part == END) {
        return null;
      }
      if (part instanceof Throwable error) {
        throw new IOException("Could not download " + uri + ": " + error.getMessage(), error);
      }
      subscription.request(1);
      @SuppressWarnings("unchecked")
      List<ByteBuffer> buffers = (List<ByteBuffer>) part;
      return buffers;
    }

    void cancel() {
      cancelled = true;
      Flow.Subscription s = subscription;
      if (s != null) {
        s.cancel();
      }
    }
  }
}
