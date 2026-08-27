/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service.translation;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

@Test
public class ClaudeTranslatorTest extends TranslatorTestBase {
  @Test
  public void copiesEverythingVerbatim() {
    write("skills/skill1/SKILL.md", "---\nname: skill1\n---\nskill");
    write("rules/a.md", SCOPED_RULE);
    write("agents/agent1.md", AGENT);
    write("claude/settings.json", "{}");
    write("claude/commands/go.md", "go");
    write("codex/config.toml", "ignored");
    write("README.md", "ignored");

    var out = translate(new ClaudeTranslator());
    assertEquals(paths(out), List.of(
        ".claude/agents/agent1.md",
        ".claude/rules/a.md",
        ".claude/skills/skill1/SKILL.md",
        ".claude/commands/go.md",
        ".claude/settings.json"));
    // Frontmatter included: Claude Code reads it, so nothing is stripped.
    assertEquals(fileAt(out, ".claude/rules/a.md").content(), SCOPED_RULE);
    assertEquals(fileAt(out, ".claude/agents/agent1.md").content(), AGENT);
  }

  @Test
  public void producesNothingFromAnEmptyTree() {
    assertEquals(translate(new ClaudeTranslator()), List.of());
  }
}
