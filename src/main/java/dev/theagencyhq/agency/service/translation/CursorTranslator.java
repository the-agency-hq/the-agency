/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Cursor reads: one {@code .cursor/rules/<name>.mdc} per rule, and the {@code cursor/} escape
 * hatch. Cursor's rule frontmatter has its own glob field, so a scoped rule becomes {@code globs} with
 * {@code alwaysApply: false} and an unscoped one {@code alwaysApply: true}. Skills come from {@code .agents/skills}
 * and subagents from {@code .claude/agents}, both of which Cursor reads directly.
 */
public class CursorTranslator implements Translator {
  public static final String ESCAPE_HATCH = "cursor";
  public static final String OUTPUT_ROOT = ".cursor";
  public static final String RULES_ROOT = ".cursor/rules";
  // Written by hand rather than through the YAML writer: Cursor's frontmatter parser is looser than YAML and every
  // documented example writes the globs bare, so a quoted value is the form that might not match.
  private static final String SCOPED_RULE = """
      ---
      globs: %s
      alwaysApply: false
      ---

      %s
      """;
  private static final String UNSCOPED_RULE = """
      ---
      alwaysApply: true
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
      files.add(source.generated(RULES_ROOT + "/" + rule.name() + ".mdc", content, rule.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
