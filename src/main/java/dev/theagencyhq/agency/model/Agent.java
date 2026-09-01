/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The Agents a Brief is translated for — one constant per Agent {@code Translator}. An Organization selects the
 * ones it is interested in, and the Briefing API serves each Handler only the files those Agents read. The set is
 * fixed in code rather than in a table because each constant is backed by a Translator, which is code too.
 *
 * <p>Declaration order is alphabetical and is also the natural order, which is the order the selection is stored,
 * checksummed and rendered in.
 */
public enum Agent {
  ANTIGRAVITY("Antigravity"),
  AUGMENT("Augment"),
  CLAUDE("Claude Code"),
  CLINE("Cline"),
  CODEX("Codex"),
  COPILOT("GitHub Copilot"),
  CURSOR("Cursor"),
  DEVIN("Devin"),
  FACTORY("Factory Droid"),
  GEMINI("Gemini CLI"),
  JUNIE("JetBrains Junie"),
  KILO("Kilo Code"),
  KIMI("Kimi Code CLI"),
  KIRO("Kiro"),
  OPENCODE("OpenCode");

  private final String label;

  Agent(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
