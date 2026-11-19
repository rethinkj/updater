package com.rethinkj.updater;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The installed app, which the helper replaces once the app has exited: the app's folder, or its
 * JAR file if the app is a single JAR, and its update folder. The update folder lies outside the
 * app's folder, such as {@code updates} in {@code %LOCALAPPDATA%\MyApp}, and every version of the
 * app names the same one: there core and the helper exchange the files of the documentation's page
 * "Helper files".
 *
 * <p>The app calls {@link #result()} when it starts, {@link #writable()} before it downloads an
 * update, and {@link #restart(Download)} or {@link #replaceOnExit(Download)} with the download.
 * One process at a time uses an update folder.
 */
public final class Installation {

  /** How long a restarted app has to confirm that it runs. */
  private static final Duration CONFIRM_WITHIN = Duration.ofMinutes(2);

  private static final String HELPER_CLASS = "com/rethinkj/updater/helper/Helper.class";
  private static final boolean WINDOWS = File.separatorChar == '\\';
  private static final String JAVA = WINDOWS ? "java.exe" : "java";

  /** Lets this process start one helper, and not while it reads a result. */
  private static final Object LOCK = new Object();

  private static boolean started; // guarded by LOCK

  private final Path app;
  private final Path updates;
  private final List<String> command;
  private final Path helper;

  /**
   * @param app the app's folder, or its JAR file
   * @param updates the update folder
   * @param command the command that starts the app, one argument per element, for {@link
   *     #restart(Download)}; empty if the app does not restart
   */
  public Installation(Path app, Path updates, List<String> command) {
    this(app, updates, command, null);
  }

  private Installation(Path app, Path updates, List<String> command, Path helper) {
    this.app = app.toAbsolutePath().normalize();
    this.updates = updates.toAbsolutePath().normalize();
    this.command = List.copyOf(command);
    this.helper = helper;
  }

  /**
   * The app this process runs from a launcher that jpackage built, which sets the system property
   * {@code jpackage.app-path} to its own path; empty without it, as when the app runs from an IDE.
   * The app's folder is the launcher's on Windows and the one above {@code bin} on Linux, and the
   * launcher alone is the command that starts the app.
   */
  public static Optional<Installation> launcher(Path updates) {
    String launcher = System.getProperty("jpackage.app-path");
    if (launcher == null) {
      return Optional.empty();
    }
    Path path = Path.of(launcher).toAbsolutePath();
    Path app = WINDOWS ? path.getParent() : path.getParent().getParent();
    return Optional.of(new Installation(app, updates, List.of(path.toString())));
  }

  /**
   * This installation with the helper JAR at {@code jar}. Without it, core takes the one on the
   * app's class path, or on its module path if the module {@code com.rethinkj.updater.helper} is
   * resolved, for instance with {@code --add-modules}.
   */
  public Installation withHelper(Path jar) {
    return new Installation(app, updates, command, jar.toAbsolutePath().normalize());
  }

  /** The app's folder, or its JAR file. */
  public Path app() {
    return app;
  }

  /** The update folder. */
  public Path updates() {
    return updates;
  }

  /**
   * Whether this user may write to the app's folder and the folder above it, which the helper
   * needs to replace the app. Ask before downloading an update that could not be applied.
   */
  public boolean writable() {
    return Files.isWritable(app) && Files.isWritable(app.getParent());
  }

  /**
   * What the helper reported about the last update, once: the report is deleted as it is read.
   * Empty if there is none, or while a helper is still at work.
   *
   * <p>With the report, it deletes what the update left behind: the helper's copy in the update
   * folder and, if the app's folder is in place, what the helper built beside it, an unfinished new
   * version or, once the update is applied, the old version. A copy of the helper that Windows
   * still holds open is deleted at a later call.
   *
   * @throws IOException if the report cannot be read or deleted
   */
  public Optional<Result> result() throws IOException {
    synchronized (LOCK) {
      Path file = updates.resolve("result.properties");
      Path plan = updates.resolve("plan.properties");
      if (!Files.exists(file)) {
        if (!Files.exists(plan)) { // else a helper is at work, or was ended before it reported
          deleteAll(updates, "helper-", 0);
        }
        return Optional.empty();
      }
      Result result = Result.read(file);
      // The helper names what it builds by the time, after the plan: older names are not its own.
      long since =
          Files.exists(plan) ? Files.getLastModifiedTime(plan).toMillis() - 60_000 : Long.MAX_VALUE;
      Files.deleteIfExists(plan); // first: a plan without a result means a helper at work
      Files.delete(file);
      deleteAll(updates, "helper-", 0);
      if (Files.exists(app)) { // else the old version beside it may be the only one left
        String name = app.getFileName().toString();
        deleteAll(app.getParent(), name + ".new-", since);
        if (result.status() == Result.Status.APPLIED) {
          deleteAll(app.getParent(), name + ".backup-", since);
        }
      }
      return Optional.of(result);
    }
  }

  /**
   * Starts the helper, which replaces the app with {@code download} once this process has exited.
   * The app exits next, as soon as it safely can. So far the helper does not start it again, and
   * this does what {@link #replaceOnExit(Download)} does.
   *
   * @throws IllegalStateException if this installation has no command, or this process has
   *     started a helper already
   * @throws IOException if the helper cannot be found or started
   */
  public void restart(Download download) throws IOException {
    if (command.isEmpty()) {
      throw new IllegalStateException("No command to restart " + app + " with");
    }
    start(download, Plan.Mode.RESTART);
  }

  /**
   * Starts the helper, which replaces the app with {@code download} whenever this process exits,
   * and does not start it again.
   *
   * @throws IllegalStateException if this process has started a helper already
   * @throws IOException if the helper cannot be found or started
   */
  public void replaceOnExit(Download download) throws IOException {
    start(download, Plan.Mode.EXIT);
  }

  private void start(Download download, Plan.Mode mode) throws IOException {
    synchronized (LOCK) {
      if (started) {
        throw new IllegalStateException("A helper already waits for this process to exit");
      }
      start(download, mode, helper());
      started = true;
    }
  }

  private void start(Download download, Plan.Mode mode, Path jar) throws IOException {
    Plan.format(jar); // also rejects a JAR that holds the helper's classes but is the app's own
    Path java = Path.of(System.getProperty("java.home"));
    if (!Files.isRegularFile(java.resolve("bin").resolve(JAVA))) {
      throw new IOException(java + " has no bin/" + JAVA + " to run the helper with; jpackage"
          + " leaves it out unless its --jlink-options omit --strip-native-commands");
    }
    Files.createDirectories(updates);
    Files.deleteIfExists(updates.resolve("result.properties"));
    Files.deleteIfExists(updates.resolve("confirmed.properties"));
    Path copy = Files.createDirectory(updates.resolve("helper-" + System.currentTimeMillis()));
    if (java.toRealPath().startsWith(app.toRealPath())) {
      // A runtime in the app's folder is replaced with it: on Windows its open files would keep
      // the folder from being renamed, and on Linux the helper would find the new version's.
      copy(java, copy.resolve("runtime"));
      java = copy.resolve("runtime");
    }
    Path helperJar = Files.copy(jar, copy.resolve("rethinkj-updater-helper.jar"));
    Plan.Kind kind = Files.isDirectory(app) ? Plan.Kind.ARCHIVE : Plan.Kind.JAR;
    Path file = download.file().toAbsolutePath();
    Path plan = new Plan(
            ProcessHandle.current().pid(),
            app,
            kind,
            file,
            download.sha256(),
            mode,
            command,
            CONFIRM_WITHIN)
        .write(updates);
    List<String> helperCommand = new ArrayList<>();
    if (!WINDOWS) {
      // A session of its own: it outlives the terminal the app runs in, and Ctrl+C there.
      helperCommand.add("setsid");
    }
    helperCommand.addAll(List.of(
        java.resolve("bin").resolve(JAVA).toString(),
        "-jar",
        helperJar.toString(),
        plan.toString()));
    try {
      new ProcessBuilder(helperCommand)
          .directory(copy.toFile()) // not the app's folder, which it renames
          .redirectErrorStream(true)
          .redirectOutput(updates.resolve("helper.log").toFile()) // only if its JVM fails
          .start();
    } catch (IOException e) {
      Files.deleteIfExists(plan); // no helper is at work
      throw e;
    }
  }

  /** The helper JAR: the one given, or the one that holds the helper's classes. */
  private Path helper() throws IOException {
    if (helper != null) {
      return helper;
    }
    URL url = Installation.class.getClassLoader().getResource(HELPER_CLASS);
    String s = url == null ? "" : url.toString();
    String end = "!/" + HELPER_CLASS;
    if (!s.startsWith("jar:file:") || !s.endsWith(end)) {
      throw new IOException("The helper JAR is neither on the class path nor on the module path"
          + (url == null ? "" : ", but at " + url)
          + "; give its path with withHelper");
    }
    return Path.of(URI.create(s.substring("jar:".length(), s.length() - end.length())));
  }

  /** Copies a folder, with links as links and with the permissions of its files. */
  private static void copy(Path from, Path to) throws IOException {
    Files.walkFileTree(from, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        Files.createDirectories(to.resolve(from.relativize(dir)));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.copy(
            file,
            to.resolve(from.relativize(file)),
            StandardCopyOption.COPY_ATTRIBUTES,
            LinkOption.NOFOLLOW_LINKS);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /**
   * Deletes, as far as it can, what in {@code folder} is named {@code prefix} and a number of at
   * least {@code min}.
   */
  private static void deleteAll(Path folder, String prefix, long min) {
    Pattern name = Pattern.compile(Pattern.quote(prefix) + "(\\d{1,18})");
    try (DirectoryStream<Path> found = Files.newDirectoryStream(folder, p -> {
      Matcher m = name.matcher(p.getFileName().toString());
      return m.matches() && Long.parseLong(m.group(1)) >= min;
    })) {
      for (Path p : found) {
        try {
          delete(p);
        } catch (IOException e) {
          // Tried again at the next call.
        }
      }
    } catch (IOException e) {
      // Also when there is no such folder.
    }
  }

  /** Deletes a file or folder, and of a link or a Windows junction in it only the link. */
  private static void delete(Path root) throws IOException {
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        if (attrs.isOther()) { // a junction, which walkFileTree would enter
          Files.delete(dir);
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /**
   * What the helper did, from {@code result.properties} of format 1.
   *
   * @param status what became of the update
   * @param reason with {@link Status#ROLLED_BACK} or {@link Status#REJECTED}, why: {@code format},
   *     {@code checksum}, {@code locked}, {@code not-confirmed}, {@code no-permission}, {@code
   *     declined} or {@code failed}; empty with {@link Status#APPLIED}
   * @param message a sentence in English for a log, not for parsing
   */
  public record Result(Status status, String reason, String message) {

    public Result {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(message, "message");
    }

    public enum Status {
      /** The new version is in place. */
      APPLIED,
      /** The old version is back in place. */
      ROLLED_BACK,
      /** Nothing changed, or the app's folder is missing if the message says so. */
      REJECTED
    }

    static Result read(Path file) throws IOException {
      Properties p = new Properties();
      try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        p.load(in);
      } catch (IllegalArgumentException e) { // a malformed Unicode escape
        throw new IOException("Cannot read " + file + ": " + e.getMessage(), e);
      }
      String format = p.getProperty("format");
      String status = p.getProperty("status", "");
      if (!"1".equals(format) || !status.matches("applied|rolled-back|rejected")) {
        throw new IOException(file + " has format " + format + " and status " + status
            + ", which core does not read");
      }
      return new Result(
          Status.valueOf(status.toUpperCase(Locale.ROOT).replace('-', '_')),
          p.getProperty("reason", ""),
          p.getProperty("message", ""));
    }
  }
}
