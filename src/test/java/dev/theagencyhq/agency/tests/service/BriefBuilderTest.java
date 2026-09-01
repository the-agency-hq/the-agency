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
 * The builder in isolation: a repository is a map of paths to bytes and a map of paths to Git modes, which is
 * exactly what it is handed in production. No server, no database, and no temporary directories — the builder
 * touches no filesystem, so neither does this.
 *
 * <p>What each Translator produces is its own test's business. This one covers what the builder itself owns: the
 * settings marker, the union of the Translators' outputs, path validation, and the collision check.
 */
@Test
public class BriefBuilderTest {
  // A fixed id and name: the Organization's identity is nested inside the Brief and so feeds the content
  // checksum, and the determinism tests below would compare against a moving target if it were generated. The
  // builder strips everything else -- the connection and the instants -- before the Brief is checksummed.
  private static final Organization ORG = new Organization(UUID.fromString("00000000-0000-4000-8000-000000000042"),
      "fusionauth", null, null, Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L));
  private Map<String, byte[]> files;
  private Map<String, String> modes;

  @BeforeMethod
  public void beforeMethod() {
    files = new HashMap<>();
    modes = new HashMap<>();
    write("the-agency-hq-settings.json", "{\"version\":\"1.0.0\"}");
  }

  /**
   * The selection is part of what a version means -- it decides which of the files a Handler is served -- so the
   * builder embeds it and the checksum moves with it, while the files themselves are built for every Agent.
   */
  @Test
  public void buildEmbedsTheAgentSelectionAndBuildsForEveryAgent() {
    write("rules/a.md", "A");
    var everyAgent = build();

    var narrowed = new Organization(ORG.id(), ORG.name(), new Agents(List.of(Agent.CLAUDE)), null,
        ORG.insertInstant(), ORG.updateInstant());
    var brief = new BriefBuilder().build(narrowed, new RepositoryContents("commit", files, modes));
    assertEquals(brief.organization().agents(), new Agents(List.of(Agent.CLAUDE)));
    assertEquals(brief.files(), everyAgent.files());
    assertNotEquals(BriefBuilder.checksum(brief), BriefBuilder.checksum(everyAgent));
  }

  @Test
  public void binaryContentBecomesBase64() {
    byte[] binary = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
    writeBytes("skills/logo.png", binary);

    var file = build().fileAt(".claude/skills/logo.png");
    assertEquals(file.encoding(), "base64");
    assertEquals(Base64.getDecoder().decode(file.content()), binary);
    assertEquals(file.checksum(), Checksums.sha256Hex(binary));
  }

  @Test
  public void checksumIsDeterministicAndContentSensitive() {
    write("agents/z.md", agent("z", "Does Z"));
    write("rules/a.md", "A");
    write("rules/b.md", "B");

    var brief = build();

    // Each Translator emits in its own order -- the Standard one, which runs first, puts .agents/skills ahead of
    // .agents/AGENTS.md -- so asserting that the files come out sorted, not merely that two builds agree with each
    // other, is what makes this fail if the final sort were removed: iterating an unchanged map twice in the same
    // process is stable, so two back-to-back builds would still match.
    var paths = brief.files().stream().map(BriefFile::path).toList();
    assertEquals(paths, paths.stream().sorted().toList());
    assertEquals(paths.getFirst(), ".agents/AGENTS.md");
    assertEquals(paths.getLast(), ".opencode/agents/z.md");

    var first = BriefBuilder.checksum(brief);
    assertEquals(BriefBuilder.checksum(build()), first);

    write("rules/b.md", "B changed");
    assertNotEquals(BriefBuilder.checksum(build()), first);
  }

  @Test
  public void executableSourceFilesBecomeOwnerReadExecute() {
    write("skills/skill1/scripts/run.sh", "#!/bin/sh\necho hi\n");
    modes.put("skills/skill1/scripts/run.sh", TreeEntry.MODE_EXECUTABLE);
    write("skills/skill1/SKILL.md", "skill");

    var brief = build();
    for (var root : List.of(".agents", ".claude", ".kiro")) {
      assertEquals(brief.fileAt(root + "/skills/skill1/scripts/run.sh").mode(), "r-x------");
      assertEquals(brief.fileAt(root + "/skills/skill1/SKILL.md").mode(), "r--------");
    }
  }

  @DataProvider
  public Object[][] malformedSettings() {
    return new Object[][]{
        {"{\"version\":\".\"}"}, // a bare delimiter
        {"{\"version\":\"..\"}"}, // two delimiters in a row, with nothing around them
        {"{\"version\":\"\"}"}, // present but empty
        {"{\"version\":\"x.0.0\"}"}, // non-numeric major
        {"{\"version\":\"1.0.0.0\"}"}, // four dotted parts; SemVer allows at most three
        {"{}"} // version field entirely absent, not merely empty
    };
  }

  @Test
  public void mapsEverySourceDirectoryThroughEveryTranslator() {
    write("skills/skill1/SKILL.md", "skill");
    write("rules/rule1.md", "rule");
    write("agents/agent1.md", agent("agent1", "Does one thing"));
    write("claude/settings.json", "{}");
    write("codex/config.toml", "x = 1");
    write("README.md", "ignored");

    // The complete output of one of everything: the reference for what a Brief looks like. Every Translator is
    // represented, and a Translator that gains or loses an output has to change this list.
    assertEquals(build().files().stream().map(BriefFile::path).toList(), List.of(
        ".agents/AGENTS.md",
        ".agents/agents/agent1.md",
        ".agents/rules/rule1.md",
        ".agents/skills/skill1/SKILL.md",
        ".augment/agents/agent1.md",
        ".augment/rules/rule1.md",
        ".claude/agents/agent1.md",
        ".claude/rules/rule1.md",
        ".claude/settings.json",
        ".claude/skills/skill1/SKILL.md",
        ".clinerules/rule1.md",
        ".codex/agents/agent1.toml",
        ".codex/config.toml",
        ".cursor/rules/rule1.mdc",
        ".devin/rules/rule1.md",
        ".factory/droids/agent1.md",
        ".gemini/agents/agent1.md",
        ".github/agents/agent1.agent.md",
        ".github/instructions/rule1.instructions.md",
        ".junie/agents/agent1.md",
        ".junie/rules/rule1.md",
        ".kilo/agents/agent1.md",
        ".kilocode/rules/rule1.md",
        ".kimi-code/AGENTS.md",
        ".kiro/agents/agent1.md",
        ".kiro/skills/skill1/SKILL.md",
        ".kiro/steering/rule1.md",
        ".opencode/agents/agent1.md"));
  }

  @Test
  public void missionTypeFilesAreNeverEmitted() {
    write("skills/.mission-types", "Web\n");
    write("skills/SKILL.md.mission-types", "Library\n");
    write("skills/SKILL.md", "skill");

    assertEquals(build().files().stream().map(BriefFile::path).toList(),
        List.of(".agents/skills/SKILL.md", ".claude/skills/SKILL.md", ".kiro/skills/SKILL.md"));
  }

  @Test
  public void missionTypesAreAttachedToEveryDerivedFile() {
    // Authored mixed-case and in a different order than they come out: BriefFile canonicalizes them, so the
    // resolver's author-order output (asserted as-is by MissionTypeResolverTest) is not what reaches the wire.
    write("skills/.mission-types", "Web\nLibrary\n");
    write("skills/skill1/SKILL.md", "skill");

    var brief = build();
    for (var root : List.of(".agents", ".claude", ".kiro")) {
      assertEquals(brief.fileAt(root + "/skills/skill1/SKILL.md").missionTypes(), List.of("library", "web"));
    }
  }

  @Test
  public void rejectsAGitSegmentFromTheEscapeHatch() {
    write("claude/.git/config", "[core]\n\tpager = touch /tmp/pwned\n");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void rejectsAGitignoreFromTheEscapeHatch() {
    // An ordinary, plausible source file: nothing about `claude/.gitignore` looks hostile, which is exactly why it
    // has to fail here. The Handler's planner rejects the whole Location's plan once it sees it, but only after the
    // Agency has published the version and every Handler has committed it to its store and reported itself current.
    write("claude/.gitignore", "*.log\n");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test(dataProvider = "malformedSettings")
  public void rejectsAMalformedSettingsVersion(String settingsJSON) {
    write("the-agency-hq-settings.json", settingsJSON);
    write("rules/a.md", "x");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void rejectsASymbolicLink() {
    // GitHub's archive carries a link as an ordinary small file whose content is the path it points at, so nothing
    // about the bytes gives it away -- only the mode from the tree does. Dropping it silently would ship a Brief
    // that is quietly missing a file; publishing its content would ship the target path as if it were the file.
    write("rules/link.md", "../../../etc/hosts");
    modes.put("rules/link.md", TreeEntry.MODE_SYMLINK);
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void rejectsAnUnsupportedSettingsMajorVersion() {
    write("the-agency-hq-settings.json", "{\"version\":\"2.0.0\"}");
    write("rules/a.md", "x");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void rejectsDuplicateOutputPathsFromTheClaudeEscapeHatch() {
    // The shared "rules" directory and the "claude" escape hatch can independently target .claude/rules/a.md. A
    // Translator validates one output at a time and cannot see the collision -- only BriefBuilder, which sees every
    // output, can catch two different inputs landing on the same Brief file path.
    write("rules/a.md", "shared");
    write("claude/rules/a.md", "escape hatch");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void rejectsDuplicateOutputPathsFromTheCodexEscapeHatch() {
    // A translated agent and a hand-written one in the escape hatch, both at .codex/agents/a.toml.
    write("agents/a.md", agent("a", "Does A"));
    write("codex/agents/a.toml", "name = \"escape hatch\"");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void requiresTheSettingsMarker() {
    files.remove("the-agency-hq-settings.json");
    write("rules/a.md", "x");
    assertThrows(BriefBuildException.class, this::build);
  }

  @Test
  public void skipsAGenuinelyAbsentTopLevelDirectory() {
    write("rules/a.md", "x");
    // "agents", "skills" and every escape hatch hold nothing here, and their absence must not fail the build.
    assertEquals(build().files().stream().map(BriefFile::path).toList(), List.of(
        ".agents/AGENTS.md",
        ".agents/rules/a.md",
        ".augment/rules/a.md",
        ".claude/rules/a.md",
        ".clinerules/a.md",
        ".codex/config.toml",
        ".cursor/rules/a.mdc",
        ".devin/rules/a.md",
        ".github/instructions/a.instructions.md",
        ".junie/rules/a.md",
        ".kilocode/rules/a.md",
        ".kimi-code/AGENTS.md",
        ".kiro/steering/a.md"));
  }

  @Test
  public void textContentAndChecksum() {
    write("rules/a.md", "For Claude");

    var file = build().fileAt(".claude/rules/a.md");
    assertEquals(file.encoding(), "text");
    assertEquals(file.content(), "For Claude");
    assertEquals(file.mode(), "r--------");
    assertEquals(file.checksum(), Checksums.sha256Hex("For Claude".getBytes(StandardCharsets.UTF_8)));
    assertTrue(file.missionTypes().isEmpty());
  }

  private static String agent(String name, String description) {
    return "---\nname: " + name + "\ndescription: " + description + "\n---\n\nYou are " + name + ".\n";
  }

  private Brief build() {
    return new BriefBuilder().build(ORG, new RepositoryContents("commit", files, modes));
  }

  private void write(String path, String content) {
    writeBytes(path, content.getBytes(StandardCharsets.UTF_8));
  }

  private void writeBytes(String path, byte[] content) {
    files.put(path, content);
    modes.putIfAbsent(path, "100644");
  }
}
