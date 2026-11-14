package com.rethinkj.updater;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One published version of an app.
 *
 * @param version the version, read from the tag
 * @param tag the tag as published, such as {@code v1.2.0}
 * @param title the title of the release, or the tag if it has none
 * @param published when the release was published
 * @param notes the release notes as Markdown with {@code \n} line ends, empty if there are none
 * @param preRelease whether the release is a pre-release: always if its version is one
 * @param page the release's page in a browser
 * @param assets the files attached to the release
 */
public record Release(
    Version version,
    String tag,
    String title,
    Instant published,
    String notes,
    boolean preRelease,
    URI page,
    List<Asset> assets) {

  public Release {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(tag, "tag");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(published, "published");
    Objects.requireNonNull(notes, "notes");
    Objects.requireNonNull(page, "page");
    assets = List.copyOf(assets);
    preRelease = preRelease || version.isPreRelease();
  }
}
