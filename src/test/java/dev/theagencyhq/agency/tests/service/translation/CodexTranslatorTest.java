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
public class CodexTranslatorTest extends TranslatorTestBase {
  @Test
  public void agentBecomesATOMLCustomAgent() {
    write("agents/.mission-types", "Web\n");
    write("agents/reviewer.md", """
        ---
        name: code-reviewer
        description: Reviews code for "quality"
        model: opus
        tools: [Read, Grep]
        ---

        You are a reviewer.
        Say "done" when finished \\ never before.
        """);

    var out = translate(new CodexTranslator());
    assertEquals(paths(out), List.of(".codex/agents/reviewer.toml"));
    assertEquals(out.getFirst().content(), """
        name = "code-reviewer"
        description = "Reviews code for \\"quality\\""
        developer_instructions = \"""
        You are a reviewer.
        Say \\"done\\" when finished \\\\ never before.\"""
        """);
    assertEquals(out.getFirst().missionTypes(), List.of("web"));
  }

  @Test
  public void escapeHatchCopiesVerbatimWhenThereAreNoRules() {
    write("codex/config.toml", "model = \"gpt-5\"\n");
    write("codex/rules/allow.rules", "prefix_rule(pattern=[\"git\"], decision=\"allow\")\n");

    var out = translate(new CodexTranslator());
    assertEquals(paths(out), List.of(".codex/config.toml", ".codex/rules/allow.rules"));
    assertEquals(out.getFirst().content(), "model = \"gpt-5\"\n");
  }

  @Test
  public void nameFallsBackToTheFileStem() {
    write("agents/helper.md", "---\ndescription: Helps\n---\nBody\n");
    assertTrue(translate(new CodexTranslator()).getFirst().content().startsWith("name = \"helper\"\n"));
  }

  @Test
  public void nonMarkdownAgentFilesAreIgnored() {
    write("agents/notes.txt", "not an agent");
    assertEquals(translate(new CodexTranslator()), List.of());
  }

  @Test
  public void rejectsAnAgentWithoutADescription() {
    write("agents/a.md", "---\nname: a\n---\nBody\n");
    assertThrows(BriefBuildException.class, () -> translate(new CodexTranslator()));
  }

  @Test
  public void rejectsAnAgentWithoutFrontmatter() {
    write("agents/a.md", "Just a body\n");
    assertThrows(BriefBuildException.class, () -> translate(new CodexTranslator()));
  }

  @Test
  public void rejectsAnEscapeHatchConfigThatAlreadySetsDeveloperInstructions() {
    write("rules/a.md", "A");
    write("codex/config.toml", "developer_instructions = \"mine\"\n");
    assertThrows(BriefBuildException.class, () -> translate(new CodexTranslator()));
  }

  @Test
  public void rulesBecomeDeveloperInstructionsAppendedToTheEscapeHatchConfig() {
    write("rules/java.md", SCOPED_RULE);
    write("rules/plain.md", PLAIN_RULE);
    write("codex/config.toml", "model = \"gpt-5\"\n\n");

    var out = translate(new CodexTranslator());
    // The escape hatch's config.toml is absorbed, not copied alongside.
    assertEquals(paths(out), List.of(".codex/config.toml"));
    assertEquals(out.getFirst().content(), "model = \"gpt-5\"\n\n"
        + "developer_instructions = \"\"\"\n"
        + PATHS_NOTE + "\n\n# Java\n\nTwo spaces.\n\n---\n\n# Plain\n\nAlways.\"\"\"\n");
    assertTrue(out.getFirst().missionTypes().isEmpty());
  }

  @Test
  public void rulesBecomeDeveloperInstructionsInANewConfig() {
    write("rules/a.md", "A");
    assertEquals(fileAt(translate(new CodexTranslator()), ".codex/config.toml").content(),
        "developer_instructions = \"\"\"\nA\"\"\"\n");
  }

  @Test
  public void skillsAreNotItsBusiness() {
    write("skills/s/SKILL.md", "s");
    assertEquals(translate(new CodexTranslator()), List.of());
  }
}
