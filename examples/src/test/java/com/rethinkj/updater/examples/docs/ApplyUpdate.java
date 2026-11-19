package com.rethinkj.updater.examples.docs;

import com.rethinkj.updater.Download;
import com.rethinkj.updater.Installation;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The app of the page Applying updates: started by a launcher that jpackage built, it reports the
 * last update and hands a verified download to the helper, which replaces the app once it has
 * exited.
 */
public final class ApplyUpdate {

  /**
   * {@code ApplyUpdate <updates> <file> <sha256>}, with the update folder and the download as
   * {@code Downloader} returns it.
   */
  public static void main(String[] args) throws IOException {
    Path updates = Path.of(args[0]);
    Download download = new Download(Path.of(args[1]), args[2]);

    // docs:start installation
    Installation installation = Installation.launcher(updates)
        .orElseThrow(() -> new IOException("Not started by a jpackage launcher"));
    // docs:end installation

    // docs:start result
    installation
        .result()
        .ifPresent(result -> System.out.println("The last update: " + result.status() + " "
            + result.reason() + ", " + result.message()));
    // docs:end result

    // docs:start writable
    if (!installation.writable()) {
      throw new IOException("Only an administrator can update " + installation.app());
    }
    // docs:end writable

    // docs:start apply
    installation.replaceOnExit(download);
    // docs:end apply
  }
}
