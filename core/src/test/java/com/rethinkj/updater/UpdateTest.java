package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.Section.Kind;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateTest {

  private static Release release(String version, boolean preRelease) {
    return release(version, preRelease, "Notes of " + version);
  }

  private static Release release(String version, boolean preRelease, String notes) {
    return new Release(
        Version.parse(version),
        "v" + version,
        "Release " + version,
        Instant.EPOCH,
        notes,
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

  @Test
  void mergesTheSectionsOfEveryReleaseBreakingChangesFirst() {
    // googleapis/release-please v17.0.0, as release-please wrote it
    Release releasePlease = release("2.0.0", false, """
        ## [17.0.0](https://github.com/googleapis/release-please/compare/v16.18.0...v17.0.0) (2025-03-11)


        ### ⚠ BREAKING CHANGES

        * **deps:** update octokit to v20 ([#2490](https://github.com/googleapis/release-please/issues/2490))

        ### Features

        * **deps:** update octokit to v20 ([#2490](https://github.com/googleapis/release-please/issues/2490)) ([9f3b669](https://github.com/googleapis/release-please/commit/9f3b6699474b0ff1987ef3ad4ca5a96ce69d9a6a))
        """);
    // Textualize/textual v8.2.6, in the headings of Keep a Changelog
    Release keepAChangelog = release("1.9.0", false, """
        Improved text select logic. You can select text without first clicking on a content area.

        May break snapshots without affecting appearance.

        ## [8.2.6] - 2026-04-13

        ### Fixed

        - Fixed selection to the right of code fence blocks (may break some snapshots)
        - Fixed Markdown code fences losing content when switching themes https://github.com/Textualize/textual/pull/6537

        ### Added

        - Added ability to select outside of text, so the user doesn't need to be quite so precise https://github.com/Textualize/textual/pull/6523
        """);

    Update update = Update.find(Version.parse("1.0.0"), List.of(keepAChangelog, releasePlease))
        .orElseThrow();

    assertEquals(
        List.of(
            new Section(
                Kind.BREAKING,
                "⚠ BREAKING CHANGES",
                List.of(
                    "**deps:** update octokit to v20 ([#2490](https://github.com/googleapis/release-please/issues/2490))")),
            new Section(
                Kind.ADDED,
                "Features",
                List.of(
                    "**deps:** update octokit to v20 ([#2490](https://github.com/googleapis/release-please/issues/2490)) ([9f3b669](https://github.com/googleapis/release-please/commit/9f3b6699474b0ff1987ef3ad4ca5a96ce69d9a6a))",
                    "Added ability to select outside of text, so the user doesn't need to be quite so precise https://github.com/Textualize/textual/pull/6523")),
            new Section(
                Kind.FIXED,
                "Fixed",
                List.of(
                    "Fixed selection to the right of code fence blocks (may break some snapshots)",
                    "Fixed Markdown code fences losing content when switching themes https://github.com/Textualize/textual/pull/6537")),
            new Section(
                Kind.OTHER,
                "",
                List.of(
                    "Improved text select logic. You can select text without first clicking on a content area.",
                    "May break snapshots without affecting appearance."))),
        update.sections());
  }
}
