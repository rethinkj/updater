package com.rethinkj.updater.helper;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * What core asks the helper to do: {@code plan.properties} in the app's update folder, format 1 on
 * the documentation's page "Helper files".
 *
 * @param pid the process the helper waits for
 * @param app the app's folder, or the JAR file if {@code kind} is {@link Kind#JAR}
 * @param kind what {@code file} is
 * @param file the verified download
 * @param sha256 the checksum of {@code file} as core wrote it
 * @param mode whether the helper starts the app again
 * @param command the command that starts the new version; empty for {@link Mode#EXIT}
 * @param confirmWithin how long the started version has to confirm that it runs; zero for {@link
 *     Mode#EXIT}
 */
record Plan(
    long pid,
    Path app,
    Kind kind,
    Path file,
    String sha256,
    Mode mode,
    List<String> command,
    Duration confirmWithin) {

  enum Kind {
    ARCHIVE,
    JAR
  }

  enum Mode {
    RESTART,
    EXIT
  }

  /** Why the helper does not carry out a plan: the reason code of the result, and a message. */
  static final class Rejected extends Exception {
    final String reason;

    Rejected(String reason, String message) {
      super(message);
      this.reason = reason;
    }
  }

  /**
   * Reads the plan in {@code file}. Keys it does not know are ignored.
   *
   * @throws Rejected with reason {@code format} if the plan's format is not 1, or {@code failed} if
   *     it cannot be read or lacks a key
   */
  static Plan read(Path file) throws Rejected {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(in);
    } catch (IOException e) {
      throw new Rejected("failed", "Cannot read " + file + ": " + e);
    }
    // First, since a plan of another format may have none of the keys below.
    String format = p.getProperty("format");
    if (!"1".equals(format)) {
      throw new Rejected(
          "format", file + " has format " + format + ", but this helper reads only format 1");
    }
    try {
      Mode mode = Mode.valueOf(get(p, "mode").toUpperCase(Locale.ROOT));
      List<String> command = new ArrayList<>();
      Duration confirmWithin = Duration.ZERO;
      if (mode == Mode.RESTART) {
        command.add(get(p, "command.0"));
        for (String arg; (arg = p.getProperty("command." + command.size())) != null; ) {
          command.add(arg);
        }
        confirmWithin = Duration.ofSeconds(Long.parseLong(get(p, "confirm.seconds")));
      }
      return new Plan(
          Long.parseLong(get(p, "pid")),
          Path.of(get(p, "app")),
          Kind.valueOf(get(p, "kind").toUpperCase(Locale.ROOT)),
          Path.of(get(p, "file")),
          get(p, "sha256"),
          mode,
          List.copyOf(command),
          confirmWithin);
    } catch (IllegalArgumentException e) { // also a bad number or path
      throw new Rejected("failed", file + " has an invalid value: " + e.getMessage());
    }
  }

  private static String get(Properties p, String key) throws Rejected {
    String value = p.getProperty(key, "");
    if (value.isEmpty()) {
      throw new Rejected("failed", "The plan has no " + key);
    }
    return value;
  }
}
