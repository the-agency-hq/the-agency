/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module java.base;

/**
 * Writes the YAML frontmatter block of a translated file. Only the shapes a rules or agent file needs — scalar
 * strings, booleans and flat string lists — and a string is left unquoted whenever YAML allows it, because more than
 * one Agent reads its frontmatter with a parser looser than YAML and the plain form is the one every example in
 * their documentation uses.
 */
final class YAML {
  private static final Set<String> RESERVED = Set.of("true", "false", "yes", "no", "on", "off", "null", "~");

  private YAML() {
  }

  /**
   * @param values The fields, in the order they should appear.
   * @return The block, from its opening {@code ---} line through its closing one, ending in a newline.
   */
  static String frontmatter(Map<String, Object> values) {
    var out = new StringBuilder("---\n");
    values.forEach((key, value) -> {
      switch (value) {
        case Boolean b -> out.append(key).append(": ").append(b).append('\n');
        case List<?> list -> {
          out.append(key).append(":\n");
          list.forEach(item -> out.append("  - ").append(scalar(item.toString())).append('\n'));
        }
        default -> out.append(key).append(": ").append(scalar(value.toString())).append('\n');
      }
    });

    return out.append("---\n").toString();
  }

  static String scalar(String value) {
    if (plain(value)) {
      return value;
    }

    var out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        default -> out.append(c);
      }
    }

    return out.append('"').toString();
  }

  private static boolean plain(String value) {
    if (value.isEmpty() || !value.equals(value.strip()) || RESERVED.contains(value.toLowerCase(Locale.ROOT))) {
      return false;
    }

    // Indicator characters that start a different YAML construct, and the sequences that end a plain scalar early.
    if ("-?:,[]{}#&*!|>'\"%@`".indexOf(value.charAt(0)) >= 0) {
      return false;
    }

    if (value.contains(": ") || value.contains(" #") || value.endsWith(":") || value.contains("\n")
        || value.contains("\t")) {
      return false;
    }

    // A number would be read as one rather than as text.
    return !value.matches("[+-]?(\\d[\\d_]*(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?|0x[0-9a-fA-F]+|0o[0-7]+");
  }
}
