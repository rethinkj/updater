package com.rethinkj.updater;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

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

  /**
   * The asset whose whole name matches {@code name}, such as {@code app-.*-windows-x64\.zip}, or
   * empty if none does.
   *
   * @throws IllegalArgumentException if more than one asset matches, as a pattern that picks the
   *     wrong file is worse than none
   */
  public Optional<Asset> asset(Pattern name) {
    List<Asset> matches =
        assets.stream().filter(a -> name.matcher(a.name()).matches()).toList();
    if (matches.size() > 1) {
      throw new IllegalArgumentException("\"" + name + "\" matches several assets of " + tag + ": "
          + matches.stream().map(Asset::name).toList());
    }
    return matches.stream().findFirst();
  }

  /**
   * The notes split at their Markdown headings, in the order of the notes. A section's entries are
   * its list items and its paragraphs. A heading of no known kind under one of a known kind, such
   * as {@code Configuration} under {@code Breaking Changes}, has that kind. Text before the first
   * heading, and GitHub's {@code **Full Changelog**} link, form sections with an empty heading. A
   * heading of no known kind with nothing under it but deeper headings, such as GitHub's {@code
   * What's Changed} or the version above the sections of Keep a Changelog, makes no section; any
   * other heading makes one, even with nothing under it. Notes without headings are one section of
   * kind {@link Section.Kind#OTHER}.
   */
  public List<Section> sections() {
    return Section.parse(notes);
  }
}
