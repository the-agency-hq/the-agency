/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.github.*;
import dev.theagencyhq.agency.service.*;
import dev.theagencyhq.agency.util.*;

import static org.testng.Assert.*;

/**
 * The builder in isolation: a repository is a map of paths to bytes and a map of paths to Git modes, which is
 * exactly what it is handed in production. No server, no database, and no temporary directories — the builder
 * touches no filesystem, so neither does this.
 */
@Test
public class BriefBuilderTest {
  // A fixed id and name: the Organization's identity is nested inside the Brief and so feeds the content
  // checksum, and the determinism tests below would compare against a moving target if it were generated. The
  // builder strips everything else -- the connection and the instants -- before the Brief is checksummed.
  private static final Organization ORG = new Organization(UUID.fromString("00000000-0000-4000-8000-000000000042"),
      "fusionauth", null, Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L));
  private Map<String, byte[]> files;
  private Map<String, String> modes;

  @BeforeMethod
  public void beforeMethod() {
    files = new HashMap<>();
    modes = new HashMap<>();
    write("the-agency-hq-settings.json", "{\"version\":\"1.0.0\"}");
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
    write("agents/z.md", "Z");
    write("rules/a.md", "A");
    write("rules/b.md", "B");

    var brief = build();

    // The walk visits every path in sorted source order, so an unsorted build would emit both agent types' copies
    // of agents/z.md before either copy of rules/a.md. Asserting the fully output-path-sorted order here -- not
    // merely that two builds agree with each other -- is what makes this fail if the final sort were removed:
    // iterating an unchanged map twice in the same process is stable, so two back-to-back builds would still match.
    assertEquals(
        brief.files().stream().map(BriefFile::path).toList(),
        List.of(
            ".claude/agents/z.md",
            ".claude/rules/a.md",
            ".claude/rules/b.md",
            ".codex/agents/z.md",
            ".codex/rules/a.md",
            ".codex/rules/b.md"
        )
    );

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
    assertEquals(brief.fileAt(".claude/skills/skill1/scripts/run.sh").mode(), "r-x------");
    assertEquals(brief.fileAt(".codex/skills/skill1/scripts/run.sh").mode(), "r-x------");
    assertEquals(brief.fileAt(".claude/skills/skill1/SKILL.md").mode(), "r--------");
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
  public void mapsSharedDirectoriesToBothAgentTypes() {
    write("skills/skill1/SKILL.md", "skill");
    write("rules/rule1.md", "rule");
    write("agents/agent1.md", "agent");
    write("claude/settings.json", "{}");
    write("codex/config.toml", "x = 1");
    write("README.md", "ignored");

    assertEquals(build().files().stream().map(BriefFile::path).toList(), List.of(
        ".claude/agents/agent1.md",
        ".claude/rules/rule1.md",
        ".claude/settings.json",
        ".claude/skills/skill1/SKILL.md",
        ".codex/agents/agent1.md",
        ".codex/config.toml",
        ".codex/rules/rule1.md",
        ".codex/skills/skill1/SKILL.md"));
  }

  @Test
  public void missionTypeFilesAreNeverEmitted() {
    write("skills/.mission-types", "Web\n");
    write("skills/SKILL.md.mission-types", "Library\n");
    write("skills/SKILL.md", "skill");

    assertEquals(build().files().stream().map(BriefFile::path).toList(),
        List.of(".claude/skills/SKILL.md", ".codex/skills/SKILL.md"));
  }

  @Test
  public void missionTypesAreAttachedToEveryDerivedFile() {
    // Authored mixed-case and in a different order than they come out: BriefFile canonicalizes them, so the
    // resolver's author-order output (asserted as-is by MissionTypeResolverTest) is not what reaches the wire.
    write("skills/.mission-types", "Web\nLibrary\n");
    write("skills/skill1/SKILL.md", "skill");

    var brief = build();
    assertEquals(brief.fileAt(".claude/skills/skill1/SKILL.md").missionTypes(), List.of("library", "web"));
    assertEquals(brief.fileAt(".codex/skills/skill1/SKILL.md").missionTypes(), List.of("library", "web"));
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
  public void rejectsDuplicateOutputPaths() {
    // The shared "rules" directory and the "claude" escape hatch can independently target .claude/rules/a.md.
    // OutputPaths validates one path string at a time and cannot see this collision -- only BriefBuilder, which
    // sees every source file, can catch two different inputs landing on the same Brief file path.
    write("rules/a.md", "shared");
    write("claude/rules/a.md", "escape hatch");
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
    // "agents", "skills", "claude", and "codex" hold nothing here, and their absence must not fail the build.
    assertEquals(build().files().stream().map(BriefFile::path).toList(),
        List.of(".claude/rules/a.md", ".codex/rules/a.md"));
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
