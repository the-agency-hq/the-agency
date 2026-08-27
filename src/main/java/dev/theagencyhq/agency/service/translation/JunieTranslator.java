/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only JetBrains Junie reads: one {@code .junie/rules/<name>.md} per rule, one
 * {@code .junie/agents/<stem>.md} per agent, and the {@code junie/} escape hatch. Junie's rules are plain Markdown,
 * so a scoped rule carries its paths as a note. Junie combines the rules with the root {@code AGENTS.md} only when
 * the project has no {@code .junie/AGENTS.md}; a team that keeps one silences every rule here. Skills come from
 * {@code .agents/skills}, which Junie reads in trusted projects.
 */
public class JunieTranslator implements Translator {
  public static final String AGENTS_ROOT = ".junie/agents";
  public static final String ESCAPE_HATCH = "junie";
  public static final String OUTPUT_ROOT = ".junie";
  public static final String RULES_ROOT = ".junie/rules";
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

    for (var agent : source.agents()) {
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".md", agent.markdown(agent.frontmatter()),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
