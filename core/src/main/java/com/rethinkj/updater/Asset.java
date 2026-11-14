package com.rethinkj.updater;

import java.net.URI;
import java.util.Objects;

/**
 * A file attached to a release.
 *
 * @param name the file name, such as {@code app-1.2.0-linux-x64.tar.gz}
 * @param url where the file is downloaded from
 * @param size the size in bytes
 * @param sha256 the SHA-256 checksum the source reports, in lowercase hex; empty if it reports none
 */
public record Asset(String name, URI url, long size, String sha256) {

  public Asset {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(sha256, "sha256");
  }
}
