/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Kimi Code CLI reads: the folded rules as {@code .kimi-code/AGENTS.md} — Kimi's own per-project
 * instruction file, read alongside the root {@code AGENTS.md} — and the {@code kimi/} escape hatch. Skills come
 * from {@code .agents/skills} and subagents from {@code .agents/agents}, both of which Kimi reads directly.
 */
public class KimiTranslator implements Translator {
  public static final String AGENTS_FILE = ".kimi-code/AGENTS.md";
  public static final String ESCAPE_HATCH = "kimi";
  public static final String OUTPUT_ROOT = ".kimi-code";

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    var rules = source.rules();
    if (!rules.isEmpty()) {
      files.add(source.generated(AGENTS_FILE, Rule.toRulesDocument(rules, true), List.of()));
    }

    files.addAll(source.copyTree(ESCAPE_HATCH, OUTPUT_ROOT));
    return files;
  }
}
