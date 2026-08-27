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
public class StandardTranslatorTest extends TranslatorTestBase {
  @Test
  public void agentIsReducedToNameDescriptionAndPrompt() {
    write("agents/reviewer.md", AGENT);

    var out = translate(new StandardTranslator());
    assertEquals(paths(out), List.of(".agents/agents/reviewer.md"));
    assertEquals(out.getFirst().content(), "---\nname: code-reviewer\ndescription: Reviews code\n---\n\nYou review.\n");
  }

  @Test
  public void agentsFileIsNeverTheRootOneAppliesToEveryMissionTypeAndCarriesItsOwnChecksum() {
    write("rules/.mission-types", "Web\n");
    write("rules/a.md", "A");

    var out = translate(new StandardTranslator());
    assertEquals(paths(out), List.of(".agents/AGENTS.md"));

    var agents = out.getFirst();
    // The rule was scoped to Web, but a whole-file scope cannot carry a per-section one: the file goes everywhere
    // and the section's note does the scoping.
    assertTrue(agents.missionTypes().isEmpty());
    assertEquals(agents.encoding(), "text");
    assertEquals(agents.mode(), "r--------");
    assertEquals(agents.checksum(), Checksums.sha256Hex(agents.content().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void missionTypedRuleGetsAScopeNote() {
    write("rules/web/.mission-types", "Web\nLibrary\n");
    write("rules/web/a.md", "Web rule");

    assertEquals(agentsFile().content(), Rule.DOCUMENT_HEADER + "\n\n"
        + "_The following applies only to projects of type library, web._\n\n"
        + "Web rule\n");
  }

  @Test
  public void noRulesMeansNoAgentsFile() {
    write("skills/s/SKILL.md", "s");
    assertEquals(paths(translate(new StandardTranslator())), List.of(".agents/skills/s/SKILL.md"));
  }

  @Test
  public void nonMarkdownRulesAreLeftOut() {
    write("rules/a.md", "A");
    write("rules/diagram.png", "not text");
    assertEquals(agentsFile().content(), Rule.DOCUMENT_HEADER + "\n\nA\n");
  }

  @Test
  public void pathScopedRuleGetsAScopeNoteAndLosesItsFrontmatter() {
    write("rules/java.md", SCOPED_RULE);

    assertEquals(agentsFile().content(), Rule.DOCUMENT_HEADER + "\n\n"
        + PATHS_NOTE + "\n\n"
        + "# Java\n\nTwo spaces.\n");
  }

  @Test
  public void rejectsARuleThatIsNotText() {
    files.put("rules/a.md", new byte[]{(byte) 0xFF, (byte) 0xFE});
    assertThrows(BriefBuildException.class, () -> translate(new StandardTranslator()));
  }

  @Test
  public void rulesConcatenateInPathOrderWithSeparators() {
    write("rules/b.md", "\n\nB rule\n\n");
    write("rules/a.md", "A rule");
    write("rules/sub/c.md", "C rule");

    assertEquals(agentsFile().content(), Rule.DOCUMENT_HEADER + "\n\n"
        + "A rule\n\n---\n\n"
        + "B rule\n\n---\n\n"
        + "C rule\n");
  }

  @Test
  public void skillsCopyVerbatimIntoTheStandardTree() {
    write("skills/.mission-types", "Web\n");
    write("skills/s/SKILL.md", "---\nname: s\n---\nskill");
    write("skills/s/scripts/run.sh", "#!/bin/sh\n");

    var out = translate(new StandardTranslator());
    assertEquals(paths(out), List.of(".agents/skills/s/SKILL.md", ".agents/skills/s/scripts/run.sh"));
    assertEquals(out.getFirst().content(), "---\nname: s\n---\nskill");
    assertEquals(out.getFirst().missionTypes(), List.of("web"));
  }

  private BriefFile agentsFile() {
    return fileAt(translate(new StandardTranslator()), StandardTranslator.AGENTS_FILE);
  }
}
