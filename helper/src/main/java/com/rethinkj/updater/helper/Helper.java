package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The helper process: {@code java -jar rethinkj-updater-helper.jar <plan>}, where {@code <plan>}
 * is the path of {@code plan.properties} in the app's update folder. It waits until the app has
 * exited and replaces it. With the plan's mode {@code restart} it starts the new version and waits
 * for {@code confirmed.properties} in the same folder; without it, it puts the old version back
 * and starts that. What it did, it writes into {@code result.properties} in the same folder; it
 * prints nothing.
 *
 * <p>{@code java -jar rethinkj-updater-helper.jar list <folder>} writes the list of the app's own
 * files into a built app's folder, before it is packed.
 */
public final class Helper {

  /** How long the helper tries again to rename a file that another process has open. */
  private static final Duration LOCKED_FOR = Duration.ofSeconds(30);

  private Helper() {}

  /**
   * Exits with 0 if it applied the plan, 1 if it did not, and 2 if the arguments are neither a plan
   * nor {@code list <folder>}. With {@code list}, an exception tells that the list cannot be
   * written.
   */
  public static void main(String[] args) throws IOException {
    if (args.length == 2 && args[0].equals("list")) {
      Replace.writeList(Path.of(args[1]));
      return;
    }
    System.exit(args.length == 1 ? run(Path.of(args[0])) : 2);
  }

  static int run(Path file) {
    Path folder = file.toAbsolutePath().getParent();
    Plan plan = null;
    boolean running = false; // whether the version in place runs, started and confirmed
    String status = "applied";
    String reason = null;
    String message;
    try {
      plan = Plan.read(file);
      ProcessHandle.of(plan.pid()).ifPresent(app -> app.onExit().join());
      Path backup = Replace.apply(plan, LOCKED_FOR);
      message = "Replaced " + plan.app() + " with " + plan.file();
      if (plan.mode() == Plan.Mode.RESTART) {
        running = startConfirmed(plan, folder);
        if (!running) {
          Replace.rollBack(plan.app(), backup, LOCKED_FOR);
          status = "rolled-back";
          reason = "not-confirmed";
          message = "The new version did not confirm within "
              + plan.confirmWithin().toSeconds() + " s that it runs, or exited before, so "
              + plan.app() + " holds the old one again";
        }
      }
      if (status.equals("applied")) {
        Replace.delete(backup);
      }
    } catch (Plan.Rejected e) {
      status = "rejected";
      reason = e.reason;
      message = e.getMessage();
    } catch (Replace.RolledBack e) {
      status = "rolled-back";
      reason = e.reason;
      message = e.getMessage();
    } catch (Exception e) { // also a RuntimeException from a broken archive
      status = "rejected";
      reason = "failed";
      message = e.toString();
    }
    try {
      writeResult(folder, status, reason, message);
    } catch (IOException ignored) {
      // Nowhere is left to report it but the exit code.
    }
    // After the result, which the version it starts reads.
    if (plan != null && plan.mode() == Plan.Mode.RESTART && !running) {
      try {
        start(plan, folder);
      } catch (IOException ignored) {
        // Such as when the app's folder is missing; the result says why.
      }
    }
    return status.equals("applied") ? 0 : 1;
  }

  /** Starts the version in place with the plan's command. */
  private static Process start(Plan plan, Path folder) throws IOException {
    return new ProcessBuilder(plan.command())
        .directory(folder.toFile()) // not the app's folder, which a rollback renames
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  /**
   * Starts the new version and waits until it writes {@code confirmed.properties}. If it does not
   * within the plan's time, or exits before, it ends the new version and returns false.
   */
  private static boolean startConfirmed(Plan plan, Path folder) throws InterruptedException {
    Path confirmed = folder.resolve("confirmed.properties");
    Process app;
    try {
      Files.deleteIfExists(confirmed);
      app = start(plan, folder);
    } catch (IOException e) {
      return false; // such as when the new version no longer has the command's launcher
    }
    long end = System.nanoTime() + plan.confirmWithin().toNanos();
    while (true) {
      boolean exited = !app.isAlive(); // before the check: it may confirm and then exit
      if (Files.exists(confirmed)) {
        return true;
      }
      if (exited || System.nanoTime() - end >= 0) {
        stop(app);
        return false;
      }
      Thread.sleep(100);
    }
  }

  /**
   * Ends the app and every process it started, and waits for them, so that none of them holds a
   * file of the new version open: on Windows, a jpackage launcher starts itself again as its child.
   */
  private static void stop(Process app) {
    List<ProcessHandle> all =
        Stream.concat(Stream.of(app.toHandle()), app.descendants()).toList();
    all.forEach(ProcessHandle::destroyForcibly);
    for (ProcessHandle p : all) {
      p.onExit().completeOnTimeout(p, LOCKED_FOR.toSeconds(), TimeUnit.SECONDS).join();
    }
  }

  /**
   * Writes {@code result.properties} of format 1 into {@code folder}, replacing an earlier one.
   *
   * @param reason null with the status {@code applied}
   */
  static void writeResult(Path folder, String status, String reason, String message)
      throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("status", status);
    if (reason != null) {
      p.setProperty("reason", reason);
    }
    p.setProperty("message", message);
    Path temp = Files.createTempFile(folder, "result-", ".tmp");
    try {
      try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        p.store(out, null);
      }
      // Whole or not at all: the helper may be ended while it writes, when the user logs off.
      Files.move(
          temp,
          folder.resolve("result.properties"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
