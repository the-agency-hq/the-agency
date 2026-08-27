/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Devin (the agent formerly Windsurf; Cascade in the desktop app, Devin CLI in the terminal)
 * reads: one {@code .devin/rules/<name>.md} per rule and the {@code devin/} escape hatch. A scoped rule becomes
 * {@code trigger: glob} with {@code globs}, an unscoped one {@code trigger: always_on}. Devin documents a single
 * glob per rule; several are joined with commas, which is unverified. Skills come from {@code .agents/skills} and
 * subagents from {@code .agents/agents}.
 */
public class DevinTranslator implements Translator {
  public static final String ESCAPE_HATCH = "devin";
  public static final String OUTPUT_ROOT = ".devin";
  public static final String RULES_ROOT = ".devin/rules";
  // Written by hand rather than through the YAML writer: every documented example writes the globs bare, and the
  // parser behind them is not known to accept a quoted value.
  private static final String SCOPED_RULE = """
      ---
      trigger: glob
      globs: %s
      ---

      %s
      """;
  private static final String UNSCOPED_RULE = """
      ---
      trigger: always_on
      ---

      %s
      """;

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var rule : source.rules()) {
      var content = rule.scoped()
          ? SCOPED_RULE.formatted(String.join(", ", rule.paths()), rule.body())
          : UNSCOPED_RULE.formatted(rule.body());
      files.add(source.generated(RULES_ROOT + "/" + rule.name() + ".md", content, rule.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
