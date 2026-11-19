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
   * What the app does as it starts, with its update folder: it reports the last update. Given a
   * download as {@code Downloader} returns it, it then starts the helper, and the app exits.
   */
  static void start(Path updates, Download download) throws IOException {
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

    if (download == null) {
      return;
    }

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
