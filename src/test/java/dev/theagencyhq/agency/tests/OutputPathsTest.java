/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.service.BriefBuildException;
import dev.theagencyhq.agency.service.OutputPaths;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

@Test
public class OutputPathsTest {
  @Test
  public void escapeHatchesMapVerbatim() {
    assertEquals(OutputPaths.map("claude/settings.json"), List.of(".claude/settings.json"));
    assertEquals(OutputPaths.map("codex/config.toml"), List.of(".codex/config.toml"));
    assertEquals(OutputPaths.map("codex/rules/a.rule"), List.of(".codex/rules/a.rule"));
  }

  @DataProvider
  public Object[][] invalidPaths() {
    return new Object[][]{
        {""},
        {"a\u0000b.md"}, // NUL: must be rejected before any path splitting or Path.of
        {"a\nb.md"}, // newline: injects a line into the Handler's manifest/git-exclude writers
        {"ab.md"}, // DEL: any control character, not only NUL/newline
        {"/etc/passwd"},
        {"a/../b.md"},
        {"./a.md"},
        {".git/config"},
        {"tools/.git/config"},
        {"tools/.GIT/config"},
        {".handler-manifest"},
        {"a/.handler-manifest"},
        {"a/.HANDLER-MANIFEST"},
        // The Handler creates and maintains .gitignore itself, so a Brief naming it collides with the Handler's own
        // bootstrap. Rejected at any depth and case-insensitively, exactly as BriefPlanner rejects it.
        {".gitignore"},
        {"claude/.gitignore"},
        {"a/.GITIGNORE"},
        {"a/notes.md.handler-tmp-xyz"}
    };
  }

  @Test(dataProvider = "invalidPaths")
  public void rejectsInvalidPaths(String path) {
    assertThrows(BriefBuildException.class, () -> OutputPaths.validate(path));
  }

  @Test
  public void sharedDirectoriesMapToEveryAgentType() {
    assertEquals(OutputPaths.map("skills/skill1/SKILL.md"),
        List.of(".claude/skills/skill1/SKILL.md", ".codex/skills/skill1/SKILL.md"));
    assertEquals(OutputPaths.map("rules/rule1.md"), List.of(".claude/rules/rule1.md", ".codex/rules/rule1.md"));
    assertEquals(OutputPaths.map("agents/agent1.md"), List.of(".claude/agents/agent1.md", ".codex/agents/agent1.md"));
  }

  @Test
  public void unmappedRootEntriesProduceNothing() {
    assertEquals(OutputPaths.map("README.md"), List.of());
    assertEquals(OutputPaths.map("the-agency-hq-settings.json"), List.of());
    assertEquals(OutputPaths.map("docs/thing.md"), List.of());
  }

  @Test
  public void validAcceptsOrdinaryPaths() {
    OutputPaths.validate(".claude/skills/skill1/SKILL.md");
    OutputPaths.validate(".codex/config.toml");
  }
}
