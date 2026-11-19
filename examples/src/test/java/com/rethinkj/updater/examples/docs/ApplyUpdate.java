package com.rethinkj.updater.examples.docs;

import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Download;
import com.rethinkj.updater.Downloader;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Release;
import com.rethinkj.updater.Source;
import com.rethinkj.updater.Update;
import com.rethinkj.updater.Version;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The app of the page Applying updates, which updates itself: started by a launcher that jpackage
 * built, it reports the last update and confirms that it runs. Then it looks for a newer release,
 * downloads its archive for this platform, and hands it to the helper, which replaces the app once
 * it has exited and starts the new version.
 */
public final class ApplyUpdate {

  private static final boolean WINDOWS = File.separatorChar == '\\';

  /**
   * What the app does as it starts, with its update folder, the source of its releases and the
   * version it runs. If it starts the helper, the app exits next.
   */
  static void start(Path updates, Source source, Version installed) throws IOException {
    // docs:start installation
    Installation installation = Installation.launcher(updates)
        .orElseThrow(() -> new IOException("Not started by a jpackage launcher"));
    // docs:end installation

    // docs:start result
    Path downloads = installation.updates().resolve("downloads");
    Optional<Installation.Result> last = installation.result();
    if (last.isPresent()) {
      Installation.Result result = last.get();
      System.out.println(
          "The last update: " + result.status() + " " + result.reason() + ", " + result.message());
      delete(downloads); // the helper is done with them
    }
    // docs:end result

    // docs:start confirm
    installation.confirm(); // once the app is ready for use
    // docs:end confirm

    // docs:start find-update
    if (last.isPresent() && last.get().status() != Installation.Result.Status.APPLIED) {
      return; // not again at once: the same update would fail the same way
    }
    Optional<Update> update = Update.find(installed, source.releases());
    if (update.isEmpty()) {
      return;
    }
    // docs:end find-update

    // docs:start writable
    if (!installation.writable()) {
      throw new IOException("Only an administrator can update " + installation.app());
    }
    // docs:end writable

    // docs:start download-archive
    Release latest = update.get().latest();
    Asset archive = latest
        .asset(Pattern.compile(WINDOWS ? ".*-windows-x64\\.zip" : ".*-linux-x64\\.tar\\.gz"))
        .orElseThrow(() -> new IOException("No archive for this platform in " + latest.tag()));
    Download download = new Downloader()
        .download(latest, archive, Files.createDirectories(downloads), received -> {});
    // docs:end download-archive

    // docs:start apply
    installation.restart(download);
    // docs:end apply
  }

  /** Deletes the files in {@code folder} as far as it can, without keeping the app from starting. */
  private static void delete(Path folder) {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
      for (Path file : files) {
        Files.deleteIfExists(file);
      }
    } catch (IOException e) {
      // What is left is deleted after the next update; also when there is no such folder yet.
    }
  }
}
