package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;

/**
 * The helper process: {@code java -jar rethinkj-updater-helper.jar <plan>}, where {@code <plan>}
 * is the path of {@code plan.properties} in the app's update folder. It waits until the app has
 * exited and replaces it. What it did, it writes into {@code result.properties} in the same
 * folder; it prints nothing.
 *
 * <p>{@code java -jar rethinkj-updater-helper.jar list <folder>} writes the list of the app's own
 * files into a built app's folder, before it is packed.
 *
 * <p>So far it does not start the app again.
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
    try {
      Plan plan = Plan.read(file);
      ProcessHandle.of(plan.pid()).ifPresent(app -> app.onExit().join());
      Replace.delete(Replace.apply(plan, LOCKED_FOR)); // the old version, before the result
      return report(folder, "applied", null, "Replaced " + plan.app() + " with " + plan.file());
    } catch (Plan.Rejected e) {
      return report(folder, "rejected", e.reason, e.getMessage());
    } catch (Replace.RolledBack e) {
      return report(folder, "rolled-back", e.reason, e.getMessage());
    } catch (Exception e) { // also a RuntimeException from a broken archive
      return report(folder, "rejected", "failed", e.toString());
    }
  }

  private static int report(Path folder, String status, String reason, String message) {
    try {
      writeResult(folder, status, reason, message);
    } catch (IOException ignored) {
      // Nowhere is left to report it but the exit code.
    }
    return status.equals("applied") ? 0 : 1;
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
