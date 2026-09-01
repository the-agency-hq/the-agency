/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces the cross-Agent conventions under {@code .agents/}: the skills tree of the Agent Skills standard, a
 * subagents tree, and a folded rules document. Claude Code reads none of them and has its own Translator.
 *
 * <p>{@code .agents/skills/**} copies verbatim — the {@code SKILL.md} format is the same standard everywhere, and
 * Codex, Cursor, Copilot, Gemini CLI, OpenCode, Kimi, Windsurf, Roo, Amp, Zed, Junie, Factory, Goose, Warp,
 * Augment and Vibe all read it.
 *
 * <p>{@code .agents/agents/<stem>.md} is each source agent reduced to name, description and prompt; Devin, Kimi,
 * Antigravity, Junie and OpenHands read it.
 *
 * <p>{@code .agents/AGENTS.md} is every rule folded into one document. It is deliberately not the root
 * {@code AGENTS.md}: that file is the team's own committed documentation, and the Handler must never write over it.
 * Factory reads this location natively. For the Agents whose only always-on channel is the root file — Amp, Zed,
 * Warp, Vibe, Goose, OpenCode, Gemini, Qwen — the team adds one line to their own {@code AGENTS.md} pointing here:
 * an {@code @./.agents/AGENTS.md} import where the Agent supports one, a prose instruction otherwise.
 */
public class StandardTranslator implements Translator {
  public static final String AGENTS_FILE = ".agents/AGENTS.md";
  public static final String AGENTS_ROOT = ".agents/agents";
  public static final String SKILLS_DIRECTORY = "skills";
  public static final String SKILLS_ROOT = ".agents/skills";

  @Override
  public Agent agent() {
    return null;
  }

  @Override
  public boolean reads(String path) {
    return false;
  }

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<>(source.copyTree(SKILLS_DIRECTORY, SKILLS_ROOT));
    for (var agent : source.agents()) {
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".md", agent.markdown(agent.frontmatter()),
          agent.missionTypes()));
    }

    var rules = source.rules();
    if (!rules.isEmpty()) {
      files.add(source.generated(AGENTS_FILE, Rule.toRulesDocument(rules, true), List.of()));
    }

    return files;
  }
}
