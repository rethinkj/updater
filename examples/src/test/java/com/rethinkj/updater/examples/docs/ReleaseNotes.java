package com.rethinkj.updater.examples.docs;

import com.rethinkj.updater.GitHubSource;
import com.rethinkj.updater.Section;
import com.rethinkj.updater.Update;
import com.rethinkj.updater.Version;
import java.io.IOException;
import java.io.PrintStream;

/**
 * The app of the page Release notes and channels: it prints what changed in the GitHub CLI since
 * 2.103.1, by section, breaking changes first.
 */
public final class ReleaseNotes {

  public static void main() throws IOException {
    Update.find(Version.parse("2.103.1"), new GitHubSource("cli/cli").releases())
        .ifPresent(update -> print(update, System.out));
  }

  static void print(Update update, PrintStream out) {
    // docs:start sections
    for (Section section : update.sections()) {
      String title = switch (section.kind()) {
        case BREAKING -> "Breaking changes";
        case ADDED -> "New";
        case FIXED -> "Fixed";
        default -> section.heading(); // empty for text under no heading
      };
      out.println(title);
      section.entries().forEach(entry -> out.println("- " + entry));
    }
    // docs:end sections
  }
}
