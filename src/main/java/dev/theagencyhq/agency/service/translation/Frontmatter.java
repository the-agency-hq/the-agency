/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module java.base;

/**
 * The YAML frontmatter of a Markdown file — the block between two {@code ---} lines at the very top — and the body
 * after it. Reads only the subset agent files use: top-level scalars (plain, quoted, or {@code |}/{@code >} block)
 * and top-level lists (block {@code - item} or flow {@code [a, b]}). Anything else is skipped rather than rejected,
 * so a file carrying keys this parser does not understand still translates.
 *
 * @param scalars The scalar entries, in file order.
 * @param lists   The list entries, in file order.
 * @param body    Everything after the closing {@code ---}, or the whole text when there is no frontmatter.
 * @param present Whether the text had a frontmatter block at all.
 */
public record Frontmatter(Map<String, String> scalars, Map<String, List<String>> lists, String body,
                          boolean present) {
  private static final String FENCE = "---";

  public static Frontmatter parse(String text) {
    var lines = text.lines().toList();
    var trailing = text.endsWith("\n") ? "\n" : "";
    if (lines.isEmpty() || !lines.getFirst().strip().equals(FENCE)) {
      return new Frontmatter(Map.of(), Map.of(), text, false);
    }

    int end = -1;
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).strip().equals(FENCE)) {
        end = i;
        break;
      }
    }

    // An opening fence with no closing one is a horizontal rule at the top of an ordinary document, not frontmatter.
    if (end < 0) {
      return new Frontmatter(Map.of(), Map.of(), text, false);
    }

    var scalars = new LinkedHashMap<String, String>();
    var lists = new LinkedHashMap<String, List<String>>();
    int i = 1;
    while (i < end) {
      var line = lines.get(i);
      int colon = line.indexOf(':');
      if (line.isBlank() || indented(line) || line.startsWith("#") || colon < 0) {
        i++;
        continue;
      }

      var key = line.substring(0, colon).strip();
      var raw = line.substring(colon + 1).strip();
      i++;

      if (raw.isEmpty()) {
        // Only items at the first continuation line's indentation count, and only when that line is an item at
        // all: a nested mapping's own lists belong to it, not to this key.
        var items = new ArrayList<String>();
        int itemIndent = -1;
        boolean nested = false;
        for (; i < end && continues(lines.get(i)); i++) {
          var continuation = lines.get(i);
          if (continuation.isBlank()) {
            continue;
          }

          var item = continuation.strip();
          int indent = continuation.length() - continuation.stripLeading().length();
          if (itemIndent < 0) {
            itemIndent = indent;
            nested = !item.startsWith("-");
          }

          if (nested || indent != itemIndent) {
            continue;
          }

          if (item.startsWith("- ")) {
            items.add(unquote(item.substring(2).strip()));
          } else if (item.equals("-")) {
            items.add("");
          }
        }

        // No items means either an empty value or a nested mapping; both read as an empty scalar.
        if (items.isEmpty()) {
          scalars.put(key, "");
        } else {
          lists.put(key, List.copyOf(items));
        }
      } else if (raw.startsWith("|") || raw.startsWith(">")) {
        var block = new ArrayList<String>();
        for (; i < end && continues(lines.get(i)); i++) {
          block.add(lines.get(i));
        }

        scalars.put(key, blockScalar(block, raw.startsWith("|")));
      } else if (raw.startsWith("[") && raw.endsWith("]")) {
        var inner = raw.substring(1, raw.length() - 1).strip();
        lists.put(key, inner.isEmpty() ? List.of()
            : Arrays.stream(inner.split(",")).map(String::strip).map(Frontmatter::unquote).toList());
      } else {
        scalars.put(key, unquote(raw));
      }
    }

    var body = String.join("\n", lines.subList(end + 1, lines.size()));
    return new Frontmatter(Collections.unmodifiableMap(scalars), Collections.unmodifiableMap(lists),
        body.isEmpty() ? body : body + trailing, true);
  }

  /**
   * @param key A key.
   * @return The list under it, or the empty list.
   */
  public List<String> list(String key) {
    return lists.getOrDefault(key, List.of());
  }

  /**
   * @param key A key.
   * @return The scalar under it, or {@code null}.
   */
  public String scalar(String key) {
    return scalars.get(key);
  }

  private static String blockScalar(List<String> block, boolean literal) {
    while (!block.isEmpty() && block.getLast().isBlank()) {
      block.removeLast();
    }

    int indent = block.stream()
                      .filter(l -> !l.isBlank())
                      .mapToInt(l -> l.length() - l.stripLeading().length())
                      .min()
                      .orElse(0);
    var stripped = block.stream().map(l -> l.isBlank() ? "" : l.substring(indent)).toList();
    if (literal) {
      return String.join("\n", stripped);
    }

    // Folded: a line break becomes a space, and a blank line becomes a line break.
    var folded = new StringBuilder();
    for (var l : stripped) {
      if (l.isEmpty()) {
        folded.append('\n');
      } else {
        if (!folded.isEmpty() && folded.charAt(folded.length() - 1) != '\n') {
          folded.append(' ');
        }

        folded.append(l);
      }
    }

    return folded.toString();
  }

  private static boolean continues(String line) {
    return line.isBlank() || indented(line);
  }

  private static boolean indented(String line) {
    return line.startsWith(" ") || line.startsWith("\t");
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      var inner = value.substring(1, value.length() - 1);
      var out = new StringBuilder();
      for (int i = 0; i < inner.length(); i++) {
        char c = inner.charAt(i);
        if (c == '\\' && i + 1 < inner.length()) {
          char next = inner.charAt(++i);
          switch (next) {
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            default -> out.append(next);
          }
        } else {
          out.append(c);
        }
      }

      return out.toString();
    }

    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1).replace("''", "'");
    }

    return value;
  }
}
