package com.rethinkj.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.updater.Section.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionTest {

  @Test
  void splitsTheNotesGitHubGeneratesAndKeepsUnknownHeadings() {
    // cli/cli v2.103.1 as GitHub generated it
    String notes = """
        ## What's Changed

        ### 🐛 Fixes

        * Avoid repeating the skill installation safety warning by @tommaso-moro in https://github.com/cli/cli/pull/14784
        * feat(gh auth login): hint at `--expiring` when no preference is set by @babakks in https://github.com/cli/cli/pull/14798

        ### 📚 Docs & Chores

        * Document extrepo installation on Debian by @williammartin in https://github.com/cli/cli/pull/14796

        ### :dependabot: Dependencies

        * Update github.com/digitorus/pkcs7 dependency by @bdehamer in https://github.com/cli/cli/pull/14791

        **Full Changelog**: https://github.com/cli/cli/compare/v2.103.0...v2.103.1
        """;

    assertEquals(
        List.of(
            new Section(
                Kind.FIXED,
                "🐛 Fixes",
                List.of(
                    "Avoid repeating the skill installation safety warning by @tommaso-moro in https://github.com/cli/cli/pull/14784",
                    "feat(gh auth login): hint at `--expiring` when no preference is set by @babakks in https://github.com/cli/cli/pull/14798")),
            new Section(
                Kind.OTHER,
                "📚 Docs & Chores",
                List.of(
                    "Document extrepo installation on Debian by @williammartin in https://github.com/cli/cli/pull/14796")),
            new Section(
                Kind.OTHER,
                ":dependabot: Dependencies",
                List.of(
                    "Update github.com/digitorus/pkcs7 dependency by @bdehamer in https://github.com/cli/cli/pull/14791")),
            new Section(
                Kind.OTHER,
                "",
                List.of(
                    "**Full Changelog**: https://github.com/cli/cli/compare/v2.103.0...v2.103.1"))),
        Section.parse(notes));
  }

  @Test
  void keepsEveryHeadingAndWhatIsUnderItInTheOrderOfTheNotes() {
    String notes = """
        Read this before you update.

        ## Features

        ### ⚠ BREAKING CHANGES

        #### Java

        * Needs Java 25:
          * on Linux, install it first

        Install Java 25 before you update.

        ## Linux builds are not ready yet

        **Full Changelog**: https://github.com/rethinkj/updater/compare/v1.0.0...v2.0.0
        """;

    assertEquals(
        List.of(
            new Section(Kind.OTHER, "", List.of("Read this before you update.")),
            new Section(Kind.ADDED, "Features", List.of()),
            new Section(Kind.BREAKING, "⚠ BREAKING CHANGES", List.of()),
            new Section(
                Kind.BREAKING,
                "Java",
                List.of(
                    "Needs Java 25:\n* on Linux, install it first",
                    "Install Java 25 before you update.")),
            new Section(Kind.OTHER, "Linux builds are not ready yet", List.of()),
            new Section(
                Kind.OTHER,
                "",
                List.of(
                    "**Full Changelog**: https://github.com/rethinkj/updater/compare/v1.0.0...v2.0.0"))),
        Section.parse(notes));
  }

  @Test
  void keepsTextWithoutHeadingsAsOneSection() {
    String notes = """
        Starts faster
        and downloads less.

        Check the version with:

        ```sh
        # prints 2.0.0
        app --version

        - not a list item
        ```text
        # not a heading
        ```

        - ```sh
          # an item can be code
          ```
        """;

    assertEquals(
        List.of(new Section(
            Kind.OTHER,
            "",
            List.of(
                "Starts faster\nand downloads less.",
                "Check the version with:",
                "```sh\n# prints 2.0.0\napp --version\n\n- not a list item\n```text\n# not a heading\n```",
                "```sh\n# an item can be code\n```"))),
        Section.parse(notes));
  }
}
