/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The reduction in isolation: a stored Brief, an Organization with a selection, and what a Handler is served. No
 * builder, no database. Which files each Agent reads is each Translator's answer, so the matrix here is the
 * contract those answers add up to.
 */
@Test
public class BriefReducerTest {
  // One file every Agent reads only through its own root, keyed by the Agent. Each path is unique to its Agent, so
  // serving one Agent must serve exactly one of these.
  private static final Map<Agent, String> OWN = new EnumMap<>(Map.ofEntries(
      Map.entry(Agent.ANTIGRAVITY, ".agents/rules/a.md"),
      Map.entry(Agent.AUGMENT, ".augment/rules/a.md"),
      Map.entry(Agent.CLAUDE, ".claude/rules/a.md"),
      Map.entry(Agent.CLINE, ".clinerules/a.md"),
      Map.entry(Agent.CODEX, ".codex/config.toml"),
      Map.entry(Agent.COPILOT, ".github/instructions/a.instructions.md"),
      Map.entry(Agent.CURSOR, ".cursor/rules/a.mdc"),
      Map.entry(Agent.DEVIN, ".devin/rules/a.md"),
      Map.entry(Agent.FACTORY, ".factory/droids/a.md"),
      Map.entry(Agent.GEMINI, ".gemini/agents/a.md"),
      Map.entry(Agent.JUNIE, ".junie/rules/a.md"),
      Map.entry(Agent.KILO, ".kilocode/rules/a.md"),
      Map.entry(Agent.KIMI, ".kimi-code/AGENTS.md"),
      Map.entry(Agent.KIRO, ".kiro/steering/a.md"),
      Map.entry(Agent.OPENCODE, ".opencode/agents/a.md")));
  private static final String AGENTS_FILE = ".agents/AGENTS.md";
  private static final String CLAUDE_AGENT = ".claude/agents/a.md";
  private static final String CLAUDE_SKILL = ".claude/skills/s/SKILL.md";
  private static final String STANDARD_AGENT = ".agents/agents/a.md";
  private static final String STANDARD_SKILL = ".agents/skills/s/SKILL.md";

  private static Brief brief(List<Agent> agents, String... paths) {
    var organization = new Organization(UUID.fromString("00000000-0000-4000-8000-000000000042"), "fusionauth",
        agents == null ? null : new Agents(agents), null, null, null);
    var files = Arrays.stream(paths)
                      .map(p -> new BriefFile(p, null, null, "x", Checksums.sha256Hex("x".getBytes()), List.of()))
                      .toList();
    return new Brief("sum", organization, 7, files, "abc", Instant.ofEpochSecond(1_700_000_000L));
  }

  private static List<String> served(List<Agent> agents, String... paths) {
    return BriefReducer.reduce(brief(agents, paths)).files().stream().map(BriefFile::path).toList();
  }

  /**
   * Every Agent reads exactly its own root out of the set of per-Agent files, and every Translator that serves an
   * Agent has that Agent listed — the enum and the Translators must agree, or a selected Agent would be served
   * nothing.
   */
  @Test
  public void eachAgentReadsItsOwnRootAndNoOtherAgents() {
    var all = OWN.values().toArray(String[]::new);
    for (var agent : Agent.values()) {
      assertEquals(served(List.of(agent), all), List.of(OWN.get(agent)), agent.name());
    }

    var translated = BriefBuilder.TRANSLATORS.stream().map(Translator::agent).filter(java.util.Objects::nonNull).toList();
    assertEquals(new HashSet<>(translated), EnumSet.allOf(Agent.class));
    assertEquals(translated.size(), Agent.values().length, "One Translator per Agent");
  }

  @Test
  public void everyAgentIsTheBriefItself() {
    var brief = brief(null, OWN.get(Agent.CLAUDE), STANDARD_SKILL, AGENTS_FILE);
    assertSame(BriefReducer.reduce(brief), brief);
  }

  /**
   * The Brief's version, checksum and provenance are what the Handler echoes, and the reduction is not a version.
   */
  @Test
  public void reductionKeepsTheVersionAndChecksum() {
    var brief = brief(List.of(Agent.CLAUDE), OWN.get(Agent.CLAUDE), OWN.get(Agent.CODEX));

    var reduced = BriefReducer.reduce(brief);
    assertEquals(reduced.version(), brief.version());
    assertEquals(reduced.checksum(), brief.checksum());
    assertEquals(reduced.sourceCommit(), brief.sourceCommit());
    assertEquals(reduced.insertInstant(), brief.insertInstant());
    assertEquals(reduced.organization(), brief.organization());
    assertEquals(reduced.files().stream().map(BriefFile::path).toList(), List.of(OWN.get(Agent.CLAUDE)));
  }

  /**
   * The shared outputs: {@code .agents/skills} goes to nearly everyone, {@code .agents/agents} and
   * {@code .agents/AGENTS.md} to the Agents whose Translators say they read them, {@code .claude/skills} to Cline and
   * {@code .claude/agents} to Cursor. Claude Code alone reads none of the {@code .agents} tree.
   */
  @Test
  public void sharedFilesFollowTheAgentsThatReadThem() {
    var shared = new String[]{STANDARD_SKILL, STANDARD_AGENT, AGENTS_FILE, CLAUDE_SKILL, CLAUDE_AGENT};

    assertEquals(served(List.of(Agent.CLAUDE), shared), List.of(CLAUDE_AGENT, CLAUDE_SKILL));
    assertEquals(served(List.of(Agent.CLINE), shared), List.of(CLAUDE_SKILL));
    assertEquals(served(List.of(Agent.CURSOR), shared), List.of(STANDARD_SKILL, CLAUDE_AGENT));
    assertEquals(served(List.of(Agent.KIRO), shared), List.of());
    for (var agent : List.of(Agent.CODEX, Agent.COPILOT, Agent.AUGMENT, Agent.KILO)) {
      assertEquals(served(List.of(agent), shared), List.of(STANDARD_SKILL), agent.name());
    }
    for (var agent : List.of(Agent.DEVIN, Agent.JUNIE, Agent.KIMI)) {
      assertEquals(served(List.of(agent), shared), List.of(STANDARD_AGENT, STANDARD_SKILL), agent.name());
    }
    for (var agent : List.of(Agent.FACTORY, Agent.GEMINI, Agent.OPENCODE)) {
      assertEquals(served(List.of(agent), shared), List.of(AGENTS_FILE, STANDARD_SKILL), agent.name());
    }
    assertEquals(served(List.of(Agent.ANTIGRAVITY), shared), List.of(AGENTS_FILE, STANDARD_AGENT, STANDARD_SKILL));

    // A file stays as long as any selected Agent reads it.
    assertEquals(served(List.of(Agent.CLAUDE, Agent.CODEX), shared), List.of(STANDARD_SKILL, CLAUDE_AGENT, CLAUDE_SKILL));
  }
}
