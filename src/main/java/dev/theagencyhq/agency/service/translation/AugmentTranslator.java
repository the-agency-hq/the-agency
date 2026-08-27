/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Augment reads: one {@code .augment/rules/<name>.md} per rule, one
 * {@code .augment/agents/<stem>.md} per agent, and the {@code augment/} escape hatch. Augment's rules have no glob
 * field — only {@code always_apply} and {@code agent_requested} — so every rule is {@code always_apply} and a
 * scoped one carries its paths as a note. Skills come from {@code .agents/skills}, which Augment reads directly.
 */
public class AugmentTranslator implements Translator {
  public static final String AGENTS_ROOT = ".augment/agents";
  public static final String ESCAPE_HATCH = "augment";
  public static final String OUTPUT_ROOT = ".augment";
  public static final String RULES_ROOT = ".augment/rules";
  private static final String RULE = """
      ---
      type: always_apply
      ---

      %s
      """;

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var rule : source.rules()) {
      files.add(source.generated(RULES_ROOT + "/" + rule.name() + ".md", RULE.formatted(rule.bodyWithPathsNote()),
          rule.missionTypes()));
    }

    for (var agent : source.agents()) {
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".md", agent.markdown(agent.frontmatter()),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
