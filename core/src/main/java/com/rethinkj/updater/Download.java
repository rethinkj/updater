package com.rethinkj.updater;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A file that {@link Downloader} downloaded and verified.
 *
 * @param file the file; the caller deletes it once it is no longer needed
 * @param sha256 its SHA-256 checksum in lowercase hex, which the helper checks again before it
 *     applies the file
 */
public record Download(Path file, String sha256) {

  public Download {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(sha256, "sha256");
  }
}
