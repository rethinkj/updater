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
   * Pre-releases are left out: this is the stable channel.
   */
  public static Optional<Update> find(Version installed, Collection<Release> releases) {
    return find(installed, releases, null);
  }

  /**
   * The update from {@code installed} to the newest of {@code releases} in {@code channel}, or
   * empty if none is newer. A channel offers every release and the pre-releases whose version has
   * it as the first identifier after the {@code -}, up to the first {@code .}:
   * {@code 0.2.0-nightly.202611190300} is in channel {@code nightly}, {@code 0.2.0-beta.1} in
   * {@code beta}. A pre-release whose version is not one is in no channel.
   *
   * @param channel the pre-release channel, such as {@code nightly}, or null for the stable channel
   */
  public static Optional<Update> find(
      Version installed, Collection<Release> releases, String channel) {
    List<Release> newer = releases.stream()
        .filter(r -> !r.preRelease() || isIn(r.version(), channel))
        .filter(r -> r.version().compareTo(installed) > 0)
        .sorted(Comparator.comparing(Release::version).reversed())
        .toList();
    return newer.isEmpty() ? Optional.empty() : Optional.of(new Update(installed, newer));
  }

  private static boolean isIn(Version version, String channel) {
    return version.isPreRelease() && version.preRelease().getFirst().equals(channel);
  }

  /** The newest release, the one to update to. */
  public Release latest() {
    return releases.getFirst();
  }
}
