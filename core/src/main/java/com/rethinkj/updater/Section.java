package com.rethinkj.updater;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A part of release notes under one heading, such as the fixes. {@link Release#sections()} splits
 * the notes of a release into sections, {@link Update#sections()} merges them over every release of
 * an update.
 *
 * @param kind what the section is about, read from its heading
 * @param heading the heading as written, without its {@code #}; empty for text under no heading
 * @param entries the section's list items without their markers, and its paragraphs, each as
 *     Markdown, in the order of the notes; empty for a heading with nothing under it
 */
public record Section(Kind kind, String heading, List<String> entries) {

  /**
   * What a section is about. The constants are in the order {@link Update#sections()} sorts by. A
   * heading is matched without regard to case, emoji, {@code :shortcodes:} and punctuation, so
   * {@code ### ⚠ BREAKING CHANGES} and {@code ### 🐛 Fixes} are recognized. New constants may be
   * added, so a {@code switch} over them needs a {@code default}.
   */
  public enum Kind {
    /** Breaking Changes, Breaking Change, Breaking. */
    BREAKING("breaking changes", "breaking change", "breaking"),
    /** Added, Features, New Features. */
    ADDED("added", "features", "new features"),
    /** Changed, Performance Improvements, Performance. */
    CHANGED("changed", "performance improvements", "performance"),
    /** Deprecated. */
    DEPRECATED("deprecated"),
    /** Removed. */
    REMOVED("removed"),
    /** Fixed, Fixes, Bug Fixes. */
    FIXED("fixed", "fixes", "bug fixes"),
    /** Security. */
    SECURITY("security"),
    /** Any other heading, kept as written, and text under no heading. */
    OTHER;

    private final List<String> headings; // in lowercase, words only

    Kind(String... headings) {
      this.headings = List.of(headings);
    }
  }

  private static final Pattern ATX_HEADING = Pattern.compile(" {0,3}(#{1,6})[ \t](.*)");
  // Indented deeper, an item is part of the one above.
  private static final Pattern ITEM = Pattern.compile(" ?(?:[-*+]|\\d{1,9}[.)])[ \t]++(.*)");
  private static final Pattern FENCE = Pattern.compile(" *+(`{3,}+|~{3,}+)[^`]*+");
  private static final Pattern SHORTCODE = Pattern.compile(":[\\w+-]+:");
  private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  public Section {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(heading, "heading");
    entries = List.copyOf(entries);
  }

  /** The sections of {@code notes}, as {@link Release#sections()} describes. */
  static List<Section> parse(String notes) {
    Reader reader = new Reader();
    notes.lines().forEach(reader::read);
    return reader.parts.stream()
        .map(p -> new Section(p.kind(), p.heading(), p.entries()))
        .toList();
  }

  /** A section while it is read, its entries still growing; level 0 for text under no heading. */
  private record Part(Kind kind, String heading, int level, List<String> entries) {}

  /**
   * Reads notes line by line into parts.
   *
   * <p>shortcut: an item's own paragraph after a blank line becomes an entry of its own, and setext
   * headings (underlined with {@code ---}) are not recognized; read Markdown blocks properly if
   * real notes need them.
   */
  private static final class Reader {
    private final List<Part> parts = new ArrayList<>();
    private Part current; // the part under the last heading, null before the first one
    // By level, the kind of the last heading there; OTHER where there is none.
    private final Kind[] above = new Kind[7];
    private List<String> open; // whose last entry the next line continues, null after a blank line
    private int indent; // the spaces the open entry's next lines lose, those of an item's text
    private String fence; // the fence of the code block being read, null outside one

    Reader() {
      Arrays.fill(above, Kind.OTHER);
    }

    private static Kind kindOf(String heading) {
      String words = NOT_A_WORD
          .matcher(SHORTCODE.matcher(heading).replaceAll(" "))
          .replaceAll(" ")
          .strip()
          .toLowerCase(Locale.ROOT);
      return Arrays.stream(Kind.values())
          .filter(k -> k.headings.contains(words))
          .findFirst()
          .orElse(Kind.OTHER);
    }

    void read(String line) {
      if (fence != null) {
        continueWith(line); // a code block is kept as written
        if (closesFence(line.strip())) {
          fence = null;
        }
        return;
      }
      Matcher heading = ATX_HEADING.matcher(line);
      Matcher item = ITEM.matcher(line);
      if (line.isBlank()) {
        open = null;
      } else if (heading.matches()) {
        startSection(heading.group(1).length(), heading.group(2).strip());
      } else if (item.matches()) {
        add(current == null ? untitled() : current.entries(), item.group(1), item.start(1));
      } else if (open != null) {
        continueWith(line);
        openFence(line);
      } else {
        addParagraph(line.stripLeading());
      }
    }

    /** Starts a code block if {@code text}, a line or an item's text, opens one. */
    private void openFence(String text) {
      Matcher opens = FENCE.matcher(text);
      if (opens.matches()) {
        fence = opens.group(1);
      }
    }

    /** Whether {@code text} closes the code block: only its fence's character, as many or more. */
    private boolean closesFence(String text) {
      return text.length() >= fence.length() && text.chars().allMatch(c -> c == fence.charAt(0));
    }

    private void startSection(int level, String heading) {
      if (current != null
          && current.kind() == Kind.OTHER
          && current.entries().isEmpty()
          && current.level() < level) {
        // Nothing but deeper headings under it, such as GitHub's What's Changed. Parts after it
        // have no heading, so the last part equal to it is it.
        parts.remove(parts.lastIndexOf(current));
      }
      current = new Part(kindAt(level, heading), heading, level, new ArrayList<>());
      parts.add(current);
      open = null;
    }

    /**
     * A heading's kind, or that of the nearest heading of a known kind it is under, such as
     * Configuration under Breaking Changes.
     */
    private Kind kindAt(int level, String heading) {
      Kind kind = kindOf(heading);
      for (int l = level - 1; kind == Kind.OTHER && l > 0; l--) {
        kind = above[l];
      }
      Arrays.fill(above, level + 1, above.length, Kind.OTHER);
      above[level] = kind;
      return kind;
    }

    private void addParagraph(String text) {
      // GitHub ends its notes with this link, which is about the whole release.
      boolean footer = text.startsWith("**Full Changelog**");
      add(current == null || footer ? untitled() : current.entries(), text, 0);
    }

    private void add(List<String> entries, String text, int indent) {
      open = entries;
      this.indent = indent;
      open.add(text);
      openFence(text);
    }

    private void continueWith(String line) {
      int cut = 0;
      while (cut < indent && cut < line.length() && line.charAt(cut) == ' ') {
        cut++;
      }
      open.set(open.size() - 1, open.getLast() + "\n" + line.substring(cut));
    }

    /** A new part without a heading, unless the last part is one. */
    private List<String> untitled() {
      if (parts.isEmpty() || parts.getLast().level() != 0) {
        parts.add(new Part(Kind.OTHER, "", 0, new ArrayList<>()));
      }
      return parts.getLast().entries();
    }
  }
}
