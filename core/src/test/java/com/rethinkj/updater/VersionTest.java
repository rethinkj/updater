package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionTest {

  @Test
  void ordersBySemVerPrecedence() {
    // The order from the SemVer specification, plus numbers that differ in length.
    List<String> ordered = List.of(
        "0.9.9",
        "1.0.0-alpha",
        "1.0.0-alpha.1",
        "1.0.0-alpha.beta",
        "1.0.0-beta",
        "1.0.0-beta.2",
        "1.0.0-beta.11",
        "1.0.0-rc.1",
        "1.0.0-rc.1.99999999999999999999",
        "1.0.0-rc.1.100000000000000000000",
        "1.0.0-rc.1.100000000000000000001",
        "1.0.0",
        "1.9.0",
        "1.10.0",
        "1.11.0",
        "2.0.0");
    for (int i = 1; i < ordered.size(); i++) {
      Version lower = Version.parse(ordered.get(i - 1));
      Version higher = Version.parse(ordered.get(i));
      assertTrue(lower.compareTo(higher) < 0, lower + " < " + higher);
      assertTrue(higher.compareTo(lower) > 0, higher + " > " + lower);
    }
  }

  @Test
  void readsTagsAndKeepsBuildMetadataOutOfPrecedence() {
    Version v = Version.parse("v1.2.3-rc.1+build.7");
    assertEquals(new Version(1, 2, 3, List.of("rc", "1"), "build.7"), v);
    assertEquals("1.2.3-rc.1+build.7", v.toString());
    assertTrue(v.isPreRelease());
    assertEquals(0, Version.parse("1.0.0+a").compareTo(Version.parse("1.0.0+b")));
  }

  @Test
  void rejectsWhatIsNotSemVer() {
    for (String text :
        List.of("", "1", "1.2", "1.2.3.4", "01.2.3", "1.2.3-01", "1.2.3-", "1.2.3+", "x1.2.3")) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> Version.parse(text), text);
      assertTrue(e.getMessage().contains("\"" + text + "\""), e.getMessage());
    }
    assertThrows(IllegalArgumentException.class, () -> Version.parse("99999999999.0.0"));
    // The constructor holds to the same rules, so a version's text reads back as the same version.
    assertThrows(IllegalArgumentException.class, () -> new Version(1, 0, 0, List.of("a.b"), ""));
    assertThrows(IllegalArgumentException.class, () -> new Version(1, 0, 0, List.of("01"), ""));
    assertThrows(IllegalArgumentException.class, () -> new Version(1, 0, 0, List.of(), "a..b"));
  }
}
