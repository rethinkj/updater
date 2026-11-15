package com.rethinkj.updater.examples.docs;

import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Downloader;
import com.rethinkj.updater.GitHubSource;
import com.rethinkj.updater.Release;
import com.rethinkj.updater.Source;
import com.rethinkj.updater.Update;
import com.rethinkj.updater.Version;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The app of the Get started pages: it checks the releases of the GitHub CLI for a version newer
 * than 2.103.1, prints the notes of every release since, and downloads the newest one's archive
 * for Linux x64, verified against its SHA-256 checksum.
 */
public final class CheckForUpdate {

  public static void main(String[] args) throws IOException {
    // docs:start source
    Source source = new GitHubSource("cli/cli");
    // docs:end source
    Path folder = Files.createTempDirectory("update-");
    Optional<Path> file = check(source, folder, System.out);
    System.out.println(file.map(f -> "Downloaded and verified " + f).orElse("Up to date"));
  }

  /** The newest release's verified archive in {@code folder}, or empty if there is none newer. */
  static Optional<Path> check(Source source, Path folder, PrintStream out) throws IOException {
    // docs:start find
    Version installed = Version.parse("2.103.1"); // the version this app runs
    Optional<Update> found = Update.find(installed, source.releases());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Update update = found.get();
    // docs:end find

    // docs:start notes
    for (Release release : update.releases()) {
      out.println("# " + release.title());
      out.println(release.notes());
    }
    // docs:end notes

    // docs:start download
    Release latest = update.latest();
    Asset archive = latest
        .asset(Pattern.compile("gh_.*_linux_amd64\\.tar\\.gz"))
        .orElseThrow(() -> new IOException("No archive for Linux x64 in " + latest.tag()));
    Path file = new Downloader().download(latest, archive, folder, received -> {});
    // docs:end download
    return Optional.of(file);
  }
}
