/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Factory Droid reads: one {@code .factory/droids/<stem>.md} per agent and the {@code factory/}
 * escape hatch. Rules reach Factory through {@code .agents/AGENTS.md}, which it reads natively, and skills through
 * {@code .agents/skills}. Factory requires a droid's {@code name} to match {@code ^[a-z0-9-_]+$}, so the name is
 * lowercased and the build fails if it still does not fit.
 */
public class FactoryTranslator implements Translator {
  public static final String DROIDS_ROOT = ".factory/droids";
  public static final String ESCAPE_HATCH = "factory";
  public static final String OUTPUT_ROOT = ".factory";
  private static final Pattern NAME = Pattern.compile("^[a-z0-9-_]+$");

  @Override
  public Agent agent() {
    return Agent.FACTORY;
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
      var name = agent.name().toLowerCase(Locale.ROOT);
      if (!NAME.matcher(name).matches()) {
        throw new BriefBuildException("The agent file [" + agent.path() + "] has the name [" + agent.name()
                                      + "], which Factory rejects. A droid name may contain only lowercase letters, "
                                      + "digits, hyphens and underscores.");
      }

      var frontmatter = agent.frontmatter();
      frontmatter.put("name", name);
      files.add(source.generated(DROIDS_ROOT + "/" + agent.stem() + ".md", agent.markdown(frontmatter),
          agent.missionTypes()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
