/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Kiro reads: one {@code .kiro/steering/<name>.md} per rule, one {@code .kiro/agents/<stem>.md}
 * per agent, a copy of the skills under {@code .kiro/skills/} — Kiro does not read {@code .agents/skills} — and the
 * {@code kiro/} escape hatch. A scoped rule becomes {@code inclusion: fileMatch} with {@code fileMatchPattern}, an
 * unscoped one {@code inclusion: always}; the Kiro CLI loads every steering file regardless of mode.
 */
public class KiroTranslator implements Translator {
  public static final String AGENTS_ROOT = ".kiro/agents";
  public static final String ESCAPE_HATCH = "kiro";
  public static final String OUTPUT_ROOT = ".kiro";
  public static final String SKILLS_ROOT = ".kiro/skills";
  public static final String STEERING_ROOT = ".kiro/steering";
  private static final String RULE = """
      %s
      %s
      """;

  @Override
  public Agent agent() {
    return Agent.KIRO;
  }

  @Override
  public boolean reads(String path) {
    return Translator.under(path, OUTPUT_ROOT);
  }

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<>(source.copyTree(StandardTranslator.SKILLS_DIRECTORY, SKILLS_ROOT));
    for (var rule : source.rules()) {
      var frontmatter = new LinkedHashMap<String, Object>();
      frontmatter.put("inclusion", rule.scoped() ? "fileMatch" : "always");
      if (rule.scoped()) {
        frontmatter.put("fileMatchPattern", rule.paths());
      }

      files.add(source.generated(STEERING_ROOT + "/" + rule.name() + ".md",
          RULE.formatted(YAML.frontmatter(frontmatter), rule.body()), rule.missionTypes()));
    }

    for (var agent : source.agents()) {
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".md", agent.markdown(agent.frontmatter()),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
