/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Gemini CLI reads: one {@code .gemini/agents/<stem>.md} per agent and the {@code gemini/}
 * escape hatch. Gemini has no rules directory; the team's {@code GEMINI.md} imports the folded rules with
 * {@code @./.agents/AGENTS.md}. Skills come from {@code .agents/skills}, which Gemini reads directly.
 */
public class GeminiTranslator implements Translator {
  public static final String AGENTS_ROOT = ".gemini/agents";
  public static final String ESCAPE_HATCH = "gemini";
  public static final String OUTPUT_ROOT = ".gemini";

  @Override
  public Agent agent() {
    return Agent.GEMINI;
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
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".md", agent.markdown(agent.frontmatter()),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
