package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateTest {

  private static Release release(String version, boolean preRelease) {
    return new Release(
        Version.parse(version),
        "v" + version,
        "Release " + version,
        Instant.EPOCH,
        "Notes of " + version,
        preRelease,
        URI.create("https://example.com/" + version),
        List.of());
  }

  @Test
  void collectsEveryNewerReleaseNewestFirstWithoutPreReleases() {
    List<Release> releases = List.of(
        release("1.1.0", false),
        release("1.0.0", false),
        release("1.3.0", false),
        release("1.4.0-beta.1", true),
        release("1.2.0", false));

    Update update = Update.find(Version.parse("1.1.0"), releases).orElseThrow();

    assertEquals("1.3.0", update.latest().version().toString());
    assertEquals(
        List.of("Notes of 1.3.0", "Notes of 1.2.0"),
        update.releases().stream().map(Release::notes).toList());
  }

  @Test
  void offersOnlyThePreReleasesOfTheChosenChannel() {
    List<Release> releases = new ArrayList<>(List.of(
        release("0.1.0", false),
        release("0.2.0-nightly.202611190300", true),
        release("0.2.0-nightly.202611200300", true),
        release("0.3.0-beta.1", true),
        release("1.0.0", true))); // marked as a pre-release, but its version names no channel
    Version installed = Version.parse("0.2.0-nightly.202611190300");

    assertEquals(
        List.of("0.2.0-nightly.202611200300"),
        versions(Update.find(installed, releases, "nightly").orElseThrow()));
    // Stable offers no step back to 0.1.0, only a release above the installed nightly.
    assertTrue(Update.find(installed, releases).isEmpty());
    releases.add(release("0.2.0", false));
    assertEquals(List.of("0.2.0"), versions(Update.find(installed, releases).orElseThrow()));
    // A release ranks above the nightlies of its version.
    assertEquals(
        List.of("0.2.0", "0.2.0-nightly.202611200300"),
        versions(Update.find(installed, releases, "nightly").orElseThrow()));
  }

  private static List<String> versions(Update update) {
    return update.releases().stream().map(r -> r.version().toString()).toList();
  }

  @Test
  void findsNothingWhenTheInstalledVersionIsTheNewest() {
    // A pre-release version counts as one even where a source does not mark it.
    List<Release> releases = List.of(release("1.0.0", false), release("2.0.0-rc.1", false));
    assertTrue(Update.find(Version.parse("1.0.0+build.5"), releases).isEmpty());
  }
}
