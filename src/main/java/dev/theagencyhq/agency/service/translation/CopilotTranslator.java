/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only GitHub Copilot reads: one {@code .github/instructions/<name>.instructions.md} per rule, one
 * {@code .github/agents/<stem>.agent.md} per agent, and the {@code copilot/} escape hatch into {@code .github/}.
 * Copilot's instruction files carry their own {@code applyTo} globs, so a scoped rule translates and an unscoped
 * one applies to {@code **}. Skills come from {@code .agents/skills}, which Copilot reads directly.
 */
public class CopilotTranslator implements Translator {
  public static final String AGENTS_ROOT = ".github/agents";
  public static final String ESCAPE_HATCH = "copilot";
  public static final String INSTRUCTIONS_ROOT = ".github/instructions";
  public static final String OUTPUT_ROOT = ".github";
  private static final String RULE = """
      %s
      %s
      """;

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var rule : source.rules()) {
      var frontmatter = new LinkedHashMap<String, Object>();
      frontmatter.put("applyTo", rule.scoped() ? String.join(",", rule.paths()) : "**");
      files.add(source.generated(INSTRUCTIONS_ROOT + "/" + rule.name() + ".instructions.md",
          RULE.formatted(YAML.frontmatter(frontmatter), rule.body()), rule.missionTypes()));
    }

    for (var agent : source.agents()) {
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".agent.md", agent.markdown(agent.frontmatter()),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
