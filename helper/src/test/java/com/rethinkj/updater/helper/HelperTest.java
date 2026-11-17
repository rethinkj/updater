package com.rethinkj.updater.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelperTest {

  @TempDir
  Path temp;

  @Test
  void readsThePlanCoreWrites() throws Exception {
    // core's PlanTest checks that core writes this file.
    Plan plan = Plan.read(Path.of("src/test/resources/plan.properties"));

    assertEquals(
        new Plan(
            4242,
            Path.of("/opt/Bücher App"),
            Plan.Kind.ARCHIVE,
            Path.of("/var/tmp/Bücher App/download-1-buecher-1.3.0-linux-x64.tar.gz"),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Plan.Mode.RESTART,
            List.of("/opt/Bücher App/bin/Bücher App", "--updated"),
            Duration.ofSeconds(120)),
        plan);
  }

  @Test
  void rejectsAPlanOfAFormatItDoesNotRead() throws IOException {
    Path folder = Files.createDirectory(temp.resolve("Bücher App updates"));
    Path plan = Files.writeString(folder.resolve("plan.properties"), "format=2\npid=4242\n");

    assertEquals(1, Helper.run(plan));

    Properties result = new Properties();
    try (Reader in =
        Files.newBufferedReader(folder.resolve("result.properties"), StandardCharsets.UTF_8)) {
      result.load(in);
    }
    assertEquals("1", result.getProperty("format"));
    assertEquals("rejected", result.getProperty("status"));
    assertEquals("format", result.getProperty("reason"));
    try (var files = Files.list(folder)) {
      assertEquals(2, files.count()); // the plan and the result, no temporary file
    }
  }
}
