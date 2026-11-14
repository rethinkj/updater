package com.rethinkj.updater;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A newer version than the installed one, and every release on the way to it.
 *
 * @param installed the version the app runs
 * @param releases the releases newer than {@code installed}, newest first; never empty
 */
public record Update(Version installed, List<Release> releases) {

  public Update {
    releases = List.copyOf(releases);
    if (releases.isEmpty()) {
      throw new IllegalArgumentException("An update needs at least one release");
    }
  }

  /**
   * The update from {@code installed} to the newest of {@code releases}, or empty if none is newer.
   * Pre-releases are left out.
   */
  public static Optional<Update> find(Version installed, Collection<Release> releases) {
    List<Release> newer = releases.stream()
        .filter(r -> !r.preRelease() && r.version().compareTo(installed) > 0)
        .sorted(Comparator.comparing(Release::version).reversed())
        .toList();
    return newer.isEmpty() ? Optional.empty() : Optional.of(new Update(installed, newer));
  }

  /** The newest release, the one to update to. */
  public Release latest() {
    return releases.getFirst();
  }
}
