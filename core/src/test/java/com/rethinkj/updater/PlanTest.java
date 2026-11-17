package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanTest {

  @TempDir
  Path folder;

  @Test
  void writesThePlanTheHelperReads() throws IOException {
    // The helper's tests read this file; only its paths take the platform's form, \ on Windows.
    Properties expected = load(Path.of("../helper/src/test/resources/plan.properties"));
    for (String key : List.of("app", "file")) {
      expected.setProperty(key, Path.of(expected.getProperty(key)).toString());
    }
    Plan plan = new Plan(
        4242,
        Path.of("/opt/Bücher App"),
        Plan.Kind.ARCHIVE,
        Path.of("/var/tmp/Bücher App/download-1-buecher-1.3.0-linux-x64.tar.gz"),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        Plan.Mode.RESTART,
        List.of("/opt/Bücher App/bin/Bücher App", "--updated"),
        Duration.ofMinutes(2));

    assertEquals(expected, load(plan.write(folder)));
  }

  @Test
  void writesOnlyAFormatTheHelperReads() throws IOException {
    assertEquals(1, Plan.format(helper("1 2")));
    assertThrows(IOException.class, () -> Plan.format(helper("2")));
  }

  private Path helper(String formats) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(new Attributes.Name(Plan.FORMATS), formats);
    Path jar = Files.createTempFile(folder, "helper-", ".jar");
    try (OutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      // only the manifest
    }
    return jar;
  }

  private static Properties load(Path file) throws IOException {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      p.load(in);
    }
    return p;
  }
}
