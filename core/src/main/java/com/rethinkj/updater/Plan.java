package com.rethinkj.updater;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * What core asks the helper to do, written as {@code plan.properties} in the app's update folder,
 * whose path is the helper's only argument. The keys are those of format 1 on the documentation's
 * page "Helper files".
 *
 * @param pid the process the helper waits for
 * @param app the app's folder, or the JAR file if {@code kind} is {@link Kind#JAR}
 * @param kind what {@code file} is
 * @param file the verified download
 * @param sha256 the checksum of {@code file}, in lowercase hex, which the helper checks again
 * @param mode whether the helper starts the app again
 * @param command the command that starts the new version; ignored by the helper for {@link
 *     Mode#EXIT}
 * @param confirmWithin how long the started version has to confirm that it runs; ignored by the
 *     helper for {@link Mode#EXIT}
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

  /** The attribute of the helper JAR's manifest that lists the plan formats it reads. */
  static final String FORMATS = "Rethinkj-Updater-Plan-Formats";

  enum Kind {
    ARCHIVE,
    JAR
  }

  enum Mode {
    /** The app exits now; the helper replaces it and starts the new version. */
    RESTART,
    /** The helper replaces the app whenever it exits, and does not start it again. */
    EXIT
  }

  /**
   * The plan format to write for the helper in {@code helperJar}: the highest one both read.
   *
   * @throws IOException if the JAR cannot be read or the helper reads none of the formats core
   *     writes
   */
  static int format(Path helperJar) throws IOException {
    Manifest manifest;
    try (JarFile jar = new JarFile(helperJar.toFile())) {
      manifest = jar.getManifest();
    }
    String formats = manifest == null ? null : manifest.getMainAttributes().getValue(FORMATS);
    // shortcut: core writes only format 1; pick the highest common one once there is a second.
    if (formats != null && List.of(formats.strip().split("\\s+")).contains("1")) {
      return 1;
    }
    throw new IOException(
        helperJar + " reads plan formats \"" + formats + "\", but this core writes only format 1");
  }

  /** Writes the plan into {@code folder}, replacing an earlier one, and returns its path. */
  Path write(Path folder) throws IOException {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("pid", Long.toString(pid));
    p.setProperty("app", app.toString());
    p.setProperty("kind", kind.name().toLowerCase(Locale.ROOT));
    p.setProperty("file", file.toString());
    p.setProperty("sha256", sha256);
    p.setProperty("mode", mode.name().toLowerCase(Locale.ROOT));
    for (int i = 0; i < command.size(); i++) {
      p.setProperty("command." + i, command.get(i));
    }
    p.setProperty("confirm.seconds", Long.toString(confirmWithin.toSeconds()));
    Path plan = folder.resolve("plan.properties");
    // A Writer, not a stream: non-ASCII characters stay as they are, in UTF-8.
    try (Writer out = Files.newBufferedWriter(plan, StandardCharsets.UTF_8)) {
      p.store(out, null);
    }
    return plan;
  }
}
