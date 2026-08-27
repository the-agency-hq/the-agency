/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module java.base;

/**
 * Writes TOML string values. Only the two string forms a translated file needs; a real TOML library would be a
 * dependency for two escaping loops.
 */
final class TOML {
  private TOML() {
  }

  /**
   * @param value Any text.
   * @return The value as a TOML basic string, double-quoted on one line.
   */
  static String basicString(String value) {
    var out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> escape(out, c);
      }
    }

    return out.append('"').toString();
  }

  /**
   * @param value Any text.
   * @return The value as a TOML multi-line basic string. Every double quote is escaped, which is always legal and
   *     is what guarantees the text can never contain the closing delimiter.
   */
  static String multilineBasicString(String value) {
    var out = new StringBuilder("\"\"\"\n");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\n', '\t' -> out.append(c);
        // A bare carriage return is not a TOML newline, so it is written as an escape rather than a character.
        case '\r' -> out.append("\\r");
        default -> escape(out, c);
      }
    }

    return out.append("\"\"\"").toString();
  }

  private static void escape(StringBuilder out, char c) {
    switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\b' -> out.append("\\b");
      case '\f' -> out.append("\\f");
      default -> {
        if (c < 0x20 || c == 0x7F) {
          out.append(String.format("\\u%04X", (int) c));
        } else {
          out.append(c);
        }
      }
    }
  }
}
