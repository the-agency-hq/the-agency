/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.service.*;
import dev.theagencyhq.agency.tests.*;
import dev.theagencyhq.agency.util.*;

import static org.testng.Assert.*;

@Test
public class BriefBuilderTest extends BaseTest {
  // A fixed id and fixed timestamps: the Organization is nested inside the Brief and so feeds the content
  // checksum, and the determinism tests below would compare against a moving target if any of it were generated.
  private static final Organization ORG = new Organization(UUID.fromString("00000000-0000-4000-8000-000000000042"),
      "fusionauth", Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L));
  private Path root;

  @AfterMethod
  public void afterMethod() throws Exception {
    deleteDirectory(root);
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    root = Files.createDirectories(Path.of("build/test/brief-builder-" + UUID.randomUUID()));
    write(root, "the-agency-hq-settings.json", "{\"version\":\"1.0.0\"}");
  }

  @Test
  public void binaryContentBecomesBase64() throws Exception {
    byte[] binary = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
    var path = root.resolve("skills/logo.png");
    Files.createDirectories(path.getParent());
    Files.write(path, binary);

    var file = new BriefBuilder().build(ORG, root).fileAt(".claude/skills/logo.png");
    assertEquals(file.encoding(), "base64");
    assertEquals(Base64.getDecoder().decode(file.content()), binary);
    assertEquals(file.checksum(), Checksums.sha256Hex(binary));
  }

  @Test
  public void checksumIsDeterministicAndContentSensitive() throws Exception {
    write(root, "agents/z.md", "Z");
    write(root, "rules/a.md", "A");
    write(root, "rules/b.md", "B");

    var brief = new BriefBuilder().build(ORG, root);

    // Source directories are walked agents-then-rules, so an unsorted build would emit both agent types' copies of
    // z.md before either copy of rules/a.md. Asserting the fully output-path-sorted order here — not merely that
    // two builds agree with each other — is what makes this test fail if the final sort were ever removed: listing
    // an unchanged directory twice in the same process is stable, so two back-to-back builds would still match.
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
    var second = BriefBuilder.checksum(new BriefBuilder().build(ORG, root));
    assertEquals(first, second);

    write(root, "rules/b.md", "B changed");
    assertNotEquals(BriefBuilder.checksum(new BriefBuilder().build(ORG, root)), first);
  }

  @Test
  public void executableSourceFilesBecomeOwnerReadExecute() throws Exception {
    var script = write(root, "skills/skill1/scripts/run.sh", "#!/bin/sh\necho hi\n");
    write(root, "skills/skill1/SKILL.md", "skill");

    var permissions = new HashSet<>(Files.getPosixFilePermissions(script));
    permissions.add(PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(script, permissions);

    var brief = new BriefBuilder().build(ORG, root);
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
  public void mapsSharedDirectoriesToBothAgentTypes() throws Exception {
    write(root, "skills/skill1/SKILL.md", "skill");
    write(root, "rules/rule1.md", "rule");
    write(root, "agents/agent1.md", "agent");
    write(root, "claude/settings.json", "{}");
    write(root, "codex/config.toml", "x = 1");
    write(root, "README.md", "ignored");

    var paths = new BriefBuilder().build(ORG, root).files().stream().map(BriefFile::path).toList();

    assertEquals(paths, List.of(
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
  public void missionTypeFilesAreNeverEmitted() throws Exception {
    write(root, "skills/.mission-types", "Web\n");
    write(root, "skills/SKILL.md.mission-types", "Library\n");
    write(root, "skills/SKILL.md", "skill");

    var paths = new BriefBuilder().build(ORG, root).files().stream().map(BriefFile::path).toList();
    assertEquals(paths, List.of(".claude/skills/SKILL.md", ".codex/skills/SKILL.md"));
  }

  @Test
  public void missionTypesAreAttachedToEveryDerivedFile() throws Exception {
    // Authored mixed-case and in a different order than they come out: BriefFile canonicalizes them, so the
    // resolver's author-order output (asserted as-is by MissionTypeResolverTest) is not what reaches the wire.
    write(root, "skills/.mission-types", "Web\nLibrary\n");
    write(root, "skills/skill1/SKILL.md", "skill");

    var brief = new BriefBuilder().build(ORG, root);
    assertEquals(brief.fileAt(".claude/skills/skill1/SKILL.md").missionTypes(), List.of("library", "web"));
    assertEquals(brief.fileAt(".codex/skills/skill1/SKILL.md").missionTypes(), List.of("library", "web"));
  }

  @Test
  public void rejectsAGitSegmentFromTheEscapeHatch() throws Exception {
    write(root, "claude/.git/config", "[core]\n\tpager = touch /tmp/pwned\n");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsAGitignoreFromTheEscapeHatch() throws Exception {
    // An ordinary, plausible source file: nothing about `claude/.gitignore` looks hostile, which is exactly why it
    // has to fail here. The Handler's planner rejects the whole Location's plan once it sees it, but only after the
    // Agency has published the version and every Handler has committed it to its store and reported itself current.
    write(root, "claude/.gitignore", "*.log\n");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test(dataProvider = "malformedSettings")
  public void rejectsAMalformedSettingsVersion(String settingsJSON) throws Exception {
    write(root, "the-agency-hq-settings.json", settingsJSON);
    write(root, "rules/a.md", "x");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsASymbolicLink() throws Exception {
    Files.createDirectories(root.resolve("rules"));
    Files.createSymbolicLink(root.resolve("rules/link.md"), Path.of("/etc/hosts"));
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsATopLevelSymbolicLinkDirectory() throws Exception {
    // The target genuinely contains a file that would have mapped, so this proves content loss is prevented, not
    // merely that some exception fires: silently dropping this file would ship an incomplete Brief with no error.
    var target = root.resolve("real-rules");
    Files.createDirectories(target);
    Files.writeString(target.resolve("a.md"), "content");
    Files.createSymbolicLink(root.resolve("rules"), target);

    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsAnUnsupportedSettingsMajorVersion() throws Exception {
    write(root, "the-agency-hq-settings.json", "{\"version\":\"2.0.0\"}");
    write(root, "rules/a.md", "x");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsDuplicateOutputPaths() throws Exception {
    // The shared "rules" directory and the "claude" escape hatch can independently target .claude/rules/a.md.
    // OutputPaths validates one path string at a time and cannot see this collision — only BriefBuilder, which
    // sees every source file, can catch two different inputs landing on the same Brief file path.
    write(root, "rules/a.md", "shared");
    write(root, "claude/rules/a.md", "escape hatch");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void requiresTheSettingsMarker() throws Exception {
    Files.delete(root.resolve("the-agency-hq-settings.json"));
    write(root, "rules/a.md", "x");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void skipsAGenuinelyAbsentTopLevelDirectory() throws Exception {
    write(root, "rules/a.md", "x");
    // "agents", "skills", "claude", and "codex" are never created here, and their absence must not fail the build.
    var paths = new BriefBuilder().build(ORG, root).files().stream().map(BriefFile::path).toList();
    assertEquals(paths, List.of(".claude/rules/a.md", ".codex/rules/a.md"));
  }

  @Test
  public void textContentAndChecksum() throws Exception {
    write(root, "rules/a.md", "For Claude");

    var brief = new BriefBuilder().build(ORG, root);
    var file = brief.fileAt(".claude/rules/a.md");
    assertEquals(file.encoding(), "text");
    assertEquals(file.content(), "For Claude");
    assertEquals(file.mode(), "r--------");
    assertEquals(file.checksum(), Checksums.sha256Hex("For Claude".getBytes(StandardCharsets.UTF_8)));
    assertTrue(file.missionTypes().isEmpty());
  }
}
