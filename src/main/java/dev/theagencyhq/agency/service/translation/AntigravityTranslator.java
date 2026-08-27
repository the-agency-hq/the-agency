/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Antigravity (Google's successor to Gemini CLI) reads: one {@code .agents/rules/<name>.md} per
 * rule and the {@code antigravity/} escape hatch into {@code .agents/}. Antigravity's rule activation modes are set
 * in its UI rather than in the file, so a scoped rule carries its paths as a note. Skills come from
 * {@code .agents/skills} and subagents from {@code .agents/agents}, both of which it reads directly.
 */
public class AntigravityTranslator implements Translator {
  public static final String ESCAPE_HATCH = "antigravity";
  public static final String OUTPUT_ROOT = ".agents";
  public static final String RULES_ROOT = ".agents/rules";
  private static final String RULE = """
      %s
      """;

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var rule : source.rules()) {
      files.add(source.generated(RULES_ROOT + "/" + rule.name() + ".md", RULE.formatted(rule.bodyWithPathsNote()),
          rule.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
