/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only OpenCode reads: one {@code .opencode/agents/<stem>.md} per agent and the {@code opencode/}
 * escape hatch. OpenCode has no rules directory and no Agency-writable instruction channel — extra instruction
 * files are listed in the team's own {@code opencode.json} — so rules reach it only through the team's pointer to
 * {@code .agents/AGENTS.md}. Skills come from {@code .agents/skills}, which OpenCode reads directly.
 */
public class OpenCodeTranslator implements Translator {
  public static final String AGENTS_ROOT = ".opencode/agents";
  public static final String ESCAPE_HATCH = "opencode";
  public static final String OUTPUT_ROOT = ".opencode";

  @Override
  public Agent agent() {
    return Agent.OPENCODE;
  }

  @Override
  public boolean reads(String path) {
    return Translator.under(path, OUTPUT_ROOT) || Translator.under(path, StandardTranslator.SKILLS_ROOT)
        || path.equals(StandardTranslator.AGENTS_FILE);
  }

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
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
