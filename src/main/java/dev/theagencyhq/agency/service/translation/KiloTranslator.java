/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Kilo Code reads: one {@code .kilocode/rules/<name>.md} per rule, one
 * {@code .kilo/agents/<stem>.md} per agent, and the {@code kilo/} escape hatch into {@code .kilo/}. The rules go to
 * the older {@code .kilocode/rules/} because Kilo still loads that directory on its own, whereas the newer
 * {@code .kilo/rules/} is read only when listed in the team's {@code kilo.jsonc}. The rules are plain Markdown, so a
 * scoped rule carries its paths as a note. Skills come from {@code .agents/skills}, which Kilo reads directly.
 */
public class KiloTranslator implements Translator {
  public static final String AGENTS_ROOT = ".kilo/agents";
  public static final String ESCAPE_HATCH = "kilo";
  public static final String OUTPUT_ROOT = ".kilo";
  public static final String RULES_ROOT = ".kilocode/rules";
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
      var frontmatter = new LinkedHashMap<String, Object>();
      frontmatter.put("description", agent.description());
      frontmatter.put("mode", "subagent");
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".md", agent.markdown(frontmatter),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
