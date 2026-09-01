/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Cline reads: one {@code .clinerules/<name>.md} per rule and the {@code cline/} escape hatch.
 * Cline's rule frontmatter uses the same {@code paths:} list as Claude Code, so a scoped rule keeps it. Cline reads
 * only the top level of {@code .clinerules/}, which is what the flat rule name is for. Skills come from
 * {@code .claude/skills}, which Cline reads directly; it has no subagents.
 */
public class ClineTranslator implements Translator {
  public static final String ESCAPE_HATCH = "cline";
  public static final String OUTPUT_ROOT = ".clinerules";
  private static final String SCOPED_RULE = """
      %s
      %s
      """;
  private static final String UNSCOPED_RULE = """
      %s
      """;

  @Override
  public Agent agent() {
    return Agent.CLINE;
  }

  @Override
  public boolean reads(String path) {
    return Translator.under(path, OUTPUT_ROOT) || Translator.under(path, ClaudeTranslator.SKILLS_ROOT);
  }

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var rule : source.rules()) {
      var content = rule.scoped()
          ? SCOPED_RULE.formatted(YAML.frontmatter(Map.of(Rule.PATHS_KEY, rule.paths())), rule.body())
          : UNSCOPED_RULE.formatted(rule.body());
      files.add(source.generated(OUTPUT_ROOT + "/" + rule.name() + ".md", content, rule.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
