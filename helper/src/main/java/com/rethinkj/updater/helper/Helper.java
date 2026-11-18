package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The helper process: {@code java -jar rethinkj-updater-helper.jar <plan>}, where {@code <plan>}
 * is the path of {@code plan.properties} in the app's update folder. What it did, it writes into
 * {@code result.properties} in the same folder; it prints nothing.
 *
 * <p>So far it reads the plan and rejects one it cannot carry out; it does not replace files yet.
 */
public final class Helper {

  private Helper() {}

  /** Exits with 0 if it accepted the plan, 1 if it rejected it, 2 without exactly one argument. */
  public static void main(String[] args) {
    System.exit(args.length == 1 ? run(Path.of(args[0])) : 2);
  }

  static int run(Path plan) {
    try {
      Plan.read(plan);
      return 0;
    } catch (Plan.Rejected e) {
      try {
        writeResult(plan.toAbsolutePath().getParent(), "rejected", e.reason, e.getMessage());
      } catch (IOException ignored) {
        // Nowhere is left to report it but the exit code.
      }
      return 1;
    }
  }

  /** Writes {@code result.properties} of format 1 into {@code folder}, replacing an earlier one. */
  static void writeResult(Path folder, String status, String reason, String message)
      throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("status", status);
    p.setProperty("reason", reason);
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
