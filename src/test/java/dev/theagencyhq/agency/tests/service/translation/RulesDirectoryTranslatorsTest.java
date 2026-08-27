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
 * The Translators that write one file per rule into an Agent's rules directory, side by side: the same two source
 * rules — one scoped by {@code paths:}, one not — and what each Agent's format makes of them.
 */
@Test
public class RulesDirectoryTranslatorsTest extends TranslatorTestBase {
  @DataProvider
  public Object[][] scopedRules() {
    return new Object[][]{
        {new CursorTranslator(), ".cursor/rules/java.mdc",
            "---\nglobs: **/*.java, project.latte\nalwaysApply: false\n---\n\n# Java\n\nTwo spaces.\n"},
        {new CopilotTranslator(), ".github/instructions/java.instructions.md",
            "---\napplyTo: \"**/*.java,project.latte\"\n---\n\n# Java\n\nTwo spaces.\n"},
        {new DevinTranslator(), ".devin/rules/java.md",
            "---\ntrigger: glob\nglobs: **/*.java, project.latte\n---\n\n# Java\n\nTwo spaces.\n"},
        {new ClineTranslator(), ".clinerules/java.md",
            "---\npaths:\n  - \"**/*.java\"\n  - project.latte\n---\n\n# Java\n\nTwo spaces.\n"},
        {new KiroTranslator(), ".kiro/steering/java.md",
            "---\ninclusion: fileMatch\nfileMatchPattern:\n  - \"**/*.java\"\n  - project.latte\n---\n\n# Java\n\n"
            + "Two spaces.\n"},
        // No glob field in these formats: the scope becomes a note ahead of the body.
        {new AugmentTranslator(), ".augment/rules/java.md",
            "---\ntype: always_apply\n---\n\n" + PATHS_NOTE + "\n\n# Java\n\nTwo spaces.\n"},
        {new AntigravityTranslator(), ".agents/rules/java.md", PATHS_NOTE + "\n\n# Java\n\nTwo spaces.\n"},
        {new JunieTranslator(), ".junie/rules/java.md", PATHS_NOTE + "\n\n# Java\n\nTwo spaces.\n"},
        {new KiloTranslator(), ".kilocode/rules/java.md", PATHS_NOTE + "\n\n# Java\n\nTwo spaces.\n"}
    };
  }

  @Test(dataProvider = "scopedRules")
  public void scopedRule(Translator translator, String path, String expected) {
    write("rules/java.md", SCOPED_RULE);

    var out = translate(translator);
    assertEquals(paths(out), List.of(path));
    assertEquals(out.getFirst().content(), expected);
  }

  @DataProvider
  public Object[][] unscopedRules() {
    return new Object[][]{
        {new CursorTranslator(), ".cursor/rules/sub-plain.mdc", "---\nalwaysApply: true\n---\n\n" + PLAIN_RULE},
        {new CopilotTranslator(), ".github/instructions/sub-plain.instructions.md",
            "---\napplyTo: \"**\"\n---\n\n" + PLAIN_RULE},
        {new DevinTranslator(), ".devin/rules/sub-plain.md", "---\ntrigger: always_on\n---\n\n" + PLAIN_RULE},
        {new ClineTranslator(), ".clinerules/sub-plain.md", PLAIN_RULE},
        {new KiroTranslator(), ".kiro/steering/sub-plain.md", "---\ninclusion: always\n---\n\n" + PLAIN_RULE},
        {new AugmentTranslator(), ".augment/rules/sub-plain.md", "---\ntype: always_apply\n---\n\n" + PLAIN_RULE},
        {new AntigravityTranslator(), ".agents/rules/sub-plain.md", PLAIN_RULE},
        {new JunieTranslator(), ".junie/rules/sub-plain.md", PLAIN_RULE},
        {new KiloTranslator(), ".kilocode/rules/sub-plain.md", PLAIN_RULE}
    };
  }

  @Test(dataProvider = "unscopedRules")
  public void unscopedRuleInASubdirectoryIsFlattenedAndKeepsItsMissionTypes(Translator translator, String path,
                                                                            String expected) {
    // Nested under rules/sub/: the output name is flattened, because several of these Agents read only the top
    // level of their rules directory. The Mission Types ride on the file, so no note mentions them.
    write("rules/sub/.mission-types", "Web\n");
    write("rules/sub/plain.md", PLAIN_RULE);

    var out = translate(translator);
    assertEquals(paths(out), List.of(path));
    assertEquals(out.getFirst().content(), expected);
    assertEquals(out.getFirst().missionTypes(), List.of("web"));
  }

  @Test
  public void escapeHatchesLandAtEachAgentsRoot() {
    write("cursor/hooks.json", "{}");
    write("copilot/copilot-instructions.md", "team");
    write("devin/workflows/deploy.md", "deploy");
    write("cline/workflows/x.md", "x");
    write("kiro/settings/mcp.json", "{}");
    write("augment/settings.json", "{}");
    write("antigravity/workflows/w.md", "w");
    write("junie/playbook.md", "p");
    write("kilo/kilo.jsonc", "{}");

    assertEquals(paths(translate(new CursorTranslator())), List.of(".cursor/hooks.json"));
    assertEquals(paths(translate(new CopilotTranslator())), List.of(".github/copilot-instructions.md"));
    assertEquals(paths(translate(new DevinTranslator())), List.of(".devin/workflows/deploy.md"));
    assertEquals(paths(translate(new ClineTranslator())), List.of(".clinerules/workflows/x.md"));
    assertEquals(paths(translate(new KiroTranslator())), List.of(".kiro/settings/mcp.json"));
    assertEquals(paths(translate(new AugmentTranslator())), List.of(".augment/settings.json"));
    assertEquals(paths(translate(new AntigravityTranslator())), List.of(".agents/workflows/w.md"));
    assertEquals(paths(translate(new JunieTranslator())), List.of(".junie/playbook.md"));
    assertEquals(paths(translate(new KiloTranslator())), List.of(".kilo/kilo.jsonc"));
  }

  @Test
  public void kiroCopiesSkillsBecauseItDoesNotReadTheStandardTree() {
    write("skills/s/SKILL.md", "s");
    assertEquals(paths(translate(new KiroTranslator())), List.of(".kiro/skills/s/SKILL.md"));
  }
}
