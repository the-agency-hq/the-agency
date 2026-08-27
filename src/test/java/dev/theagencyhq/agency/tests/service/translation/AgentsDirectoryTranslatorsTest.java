/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service.translation;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The Translators that write one Markdown file per agent, side by side: the same Claude Code subagent and what each
 * Agent's format makes of it.
 */
@Test
public class AgentsDirectoryTranslatorsTest extends TranslatorTestBase {
  private static final String NAMED = "---\nname: code-reviewer\ndescription: Reviews code\n---\n\nYou review.\n";
  private static final String SUBAGENT = "---\ndescription: Reviews code\nmode: subagent\n---\n\nYou review.\n";

  @DataProvider
  public Object[][] agents() {
    return new Object[][]{
        {new StandardTranslator(), ".agents/agents/reviewer.md", NAMED},
        {new CopilotTranslator(), ".github/agents/reviewer.agent.md", NAMED},
        {new KiroTranslator(), ".kiro/agents/reviewer.md", NAMED},
        {new AugmentTranslator(), ".augment/agents/reviewer.md", NAMED},
        {new JunieTranslator(), ".junie/agents/reviewer.md", NAMED},
        {new GeminiTranslator(), ".gemini/agents/reviewer.md", NAMED},
        {new FactoryTranslator(), ".factory/droids/reviewer.md", NAMED},
        {new KiloTranslator(), ".kilo/agents/reviewer.md", SUBAGENT},
        {new OpenCodeTranslator(), ".opencode/agents/reviewer.md", SUBAGENT}
    };
  }

  @Test(dataProvider = "agents")
  public void agentIsReducedToWhatTheAgentReads(Translator translator, String path, String expected) {
    write("agents/.mission-types", "Web\n");
    write("agents/reviewer.md", AGENT);

    var out = translate(translator);
    assertEquals(paths(out), List.of(path));
    assertEquals(out.getFirst().content(), expected);
    assertEquals(out.getFirst().missionTypes(), List.of("web"));
  }

  @Test
  public void factoryLowercasesTheNameAndRejectsOneItCannotFix() {
    write("agents/reviewer.md", "---\nname: Code-Reviewer\ndescription: Reviews\n---\nBody\n");
    assertEquals(translate(new FactoryTranslator()).getFirst().content(),
        "---\nname: code-reviewer\ndescription: Reviews\n---\n\nBody\n");

    write("agents/reviewer.md", "---\nname: code reviewer\ndescription: Reviews\n---\nBody\n");
    assertThrows(BriefBuildException.class, () -> translate(new FactoryTranslator()));
  }

  @Test
  public void frontmatterValuesThatNeedQuotingAreQuoted() {
    write("agents/tricky.md",
        "---\nname: \"yes\"\ndescription: \"Reviews: carefully, with \\\"care\\\"\"\n---\nBody\n");

    assertEquals(translate(new StandardTranslator()).getFirst().content(),
        "---\nname: \"yes\"\ndescription: \"Reviews: carefully, with \\\"care\\\"\"\n---\n\nBody\n");
  }

  @Test
  public void kimiFoldsRulesIntoItsOwnAgentsFile() {
    write("rules/a.md", "A");
    write("kimi/config.toml", "x = 1");

    var out = translate(new KimiTranslator());
    assertEquals(paths(out), List.of(".kimi-code/AGENTS.md", ".kimi-code/config.toml"));
    assertEquals(out.getFirst().content(), Rule.DOCUMENT_HEADER + "\n\nA\n");
    assertTrue(out.getFirst().missionTypes().isEmpty());
  }

  @Test
  public void rulesAndSkillsAreNotTheirBusiness() {
    write("rules/a.md", "A");
    write("skills/s/SKILL.md", "s");

    for (var translator : List.of(new FactoryTranslator(), new OpenCodeTranslator(), new GeminiTranslator())) {
      assertEquals(translate(translator), List.of(), translator.getClass().getSimpleName());
    }
  }
}
