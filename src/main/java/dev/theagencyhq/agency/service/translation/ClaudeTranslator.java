/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces the {@code .claude} tree Claude Code reads. Verbatim throughout: the source layout is Claude Code's own,
 * so {@code skills/}, {@code rules/} and {@code agents/} copy straight across, and the {@code claude/} escape hatch
 * lands at the root of the tree.
 *
 * <p>Claude Code reads nothing under {@code .agents/}, so nothing the {@link StandardTranslator} produces reaches it
 * and this Translator carries every concept itself.
 */
public class ClaudeTranslator implements Translator {
  public static final String AGENTS_ROOT = ".claude/agents";
  public static final String ESCAPE_HATCH = "claude";
  public static final String OUTPUT_ROOT = ".claude";
  public static final List<String> SHARED_DIRECTORIES = List.of("agents", "rules", "skills");
  public static final String SKILLS_ROOT = ".claude/skills";

  @Override
  public Agent agent() {
    return Agent.CLAUDE;
  }

  @Override
  public boolean reads(String path) {
    return Translator.under(path, OUTPUT_ROOT);
  }

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var directory : SHARED_DIRECTORIES) {
      files.addAll(source.copyTree(directory, OUTPUT_ROOT + "/" + directory));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
