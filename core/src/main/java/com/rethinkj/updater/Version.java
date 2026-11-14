package com.rethinkj.updater;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A version as defined by <a href="https://semver.org/spec/v2.0.0.html">Semantic Versioning
 * 2.0.0</a>, such as {@code 1.4.0}, {@code 2.0.0-beta.3} or {@code 1.0.0+build.7}.
 *
 * <p>{@link #compareTo} orders versions by SemVer precedence, which ignores build metadata: {@code
 * 1.0.0+a} and {@code 1.0.0+b} compare as equal, but are not {@link #equals equal}.
 *
 * @param major the major version
 * @param minor the minor version
 * @param patch the patch version
 * @param preRelease the dot-separated identifiers after {@code -}, empty for a release
 * @param build the build metadata after {@code +}, empty if there is none
 */
public record Version(int major, int minor, int patch, List<String> preRelease, String build)
    implements Comparable<Version> {

  private static final String IDENTIFIER = "(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)";
  private static final String BUILD = "[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*";
  private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(IDENTIFIER);
  private static final Pattern BUILD_PATTERN = Pattern.compile(BUILD);

  // The pattern semver.org recommends, with an optional "v" before it, as in Git tags.
  private static final Pattern SEMVER =
      Pattern.compile("[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
          + "(?:-(" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*))?"
          + "(?:\\+(" + BUILD + "))?");

  /**
   * @throws IllegalArgumentException if a number is negative, or an identifier or the build
   *     metadata is not valid SemVer
   */
  public Version {
    if (major < 0 || minor < 0 || patch < 0) {
      throw new IllegalArgumentException("Version numbers must not be negative");
    }
    preRelease = List.copyOf(preRelease);
    for (String id : preRelease) {
      if (!IDENTIFIER_PATTERN.matcher(id).matches()) {
        throw new IllegalArgumentException("Not a SemVer pre-release identifier: \"" + id + "\"");
      }
    }
    Objects.requireNonNull(build, "build");
    if (!build.isEmpty() && !BUILD_PATTERN.matcher(build).matches()) {
      throw new IllegalArgumentException("Not SemVer build metadata: \"" + build + "\"");
    }
  }

  /**
   * Reads a version such as {@code 1.2.0}, {@code v1.2.0} or {@code 1.2.0-rc.1+abc}.
   *
   * @throws IllegalArgumentException if the text is not a SemVer version
   */
  public static Version parse(String text) {
    Matcher m = SEMVER.matcher(text.strip());
    if (!m.matches()) {
      throw new IllegalArgumentException("Not a SemVer version: \"" + text + "\"");
    }
    try {
      return new Version(
          Integer.parseInt(m.group(1)),
          Integer.parseInt(m.group(2)),
          Integer.parseInt(m.group(3)),
          m.group(4) == null ? List.of() : List.of(m.group(4).split("\\.")),
          m.group(5) == null ? "" : m.group(5));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Version number too large: \"" + text + "\"", e);
    }
  }

  /** Whether this is a pre-release, such as {@code 1.0.0-beta.1}. */
  public boolean isPreRelease() {
    return !preRelease.isEmpty();
  }

  @Override
  public int compareTo(Version o) {
    int c = Integer.compare(major, o.major);
    if (c == 0) c = Integer.compare(minor, o.minor);
    if (c == 0) c = Integer.compare(patch, o.patch);
    if (c != 0) return c;
    // A release ranks above its pre-releases.
    if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
      return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty());
    }
    for (int i = 0; i < Math.min(preRelease.size(), o.preRelease.size()); i++) {
      c = compareIdentifiers(preRelease.get(i), o.preRelease.get(i));
      if (c != 0) return c;
    }
    return Integer.compare(preRelease.size(), o.preRelease.size());
  }

  private static int compareIdentifiers(String a, String b) {
    boolean numA = a.chars().allMatch(Character::isDigit);
    boolean numB = b.chars().allMatch(Character::isDigit);
    if (numA && numB) {
      // No leading zeros, so the longer number is the larger, whatever its size.
      return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
    if (numA != numB) {
      return numA ? -1 : 1; // numeric identifiers rank below alphanumeric ones
    }
    return a.compareTo(b);
  }

  /** The version as SemVer text, without a leading {@code v}. */
  @Override
  public String toString() {
    String s = major + "." + minor + "." + patch;
    if (!preRelease.isEmpty()) s += "-" + String.join(".", preRelease);
    if (!build.isEmpty()) s += "+" + build;
    return s;
  }
}
