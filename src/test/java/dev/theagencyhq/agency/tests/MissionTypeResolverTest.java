/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

/**
 * Pure unit tests over the resolution rules — no server, no database, no GitHub. The repository is a literal map of
 * paths to bytes, which is what the resolver reads for real.
 */
@Test
public class MissionTypeResolverTest {
  private Map<String, byte[]> files;
  private Map<String, String> modes;

  @BeforeMethod
  public void beforeMethod() {
    files = new HashMap<>();
    modes = new HashMap<>();
  }

  @Test
  public void blankLinesAndDuplicatesAreDropped() {
    write("skills/.mission-types", "Web\n\n  \nWeb\nLibrary\n");
    write("skills/a.md", "x");
    assertEquals(resolver().resolve("skills/a.md"), List.of("Web", "Library"));
  }

  @Test
  public void directoryFileAppliesToSubdirectories() {
    write("skills/.mission-types", "Web\n");
    write("skills/skill1/scripts/run.sh", "x");
    assertEquals(resolver().resolve("skills/skill1/scripts/run.sh"), List.of("Web"));
  }

  @Test
  public void fileDirectlyInSourceRootUsesRootDirectoryFile() {
    write(".mission-types", "Web\n");
    write("a.md", "x");
    assertEquals(resolver().resolve("a.md"), List.of("Web"));
  }

  @Test
  public void nearerDirectoryFileWins() {
    write("skills/.mission-types", "Web\n");
    write("skills/skill1/.mission-types", "Library\n");
    write("skills/skill1/SKILL.md", "x");
    assertEquals(resolver().resolve("skills/skill1/SKILL.md"), List.of("Library"));
  }

  @Test
  public void noFileMeansEveryMissionType() {
    write("rules/a.md", "x");
    assertEquals(resolver().resolve("rules/a.md"), List.of());
  }

  @Test
  public void originalCaseIsPreserved() {
    write("rules/.mission-types", "Web\nLIBRARY\n");
    write("rules/a.md", "x");
    assertEquals(resolver().resolve("rules/a.md"), List.of("Web", "LIBRARY"));
  }

  @Test
  public void siblingFileBeatsDirectoryFile() {
    write("skills/.mission-types", "Web\n");
    write("skills/SKILL.md.mission-types", "Library\nFramework\n");
    write("skills/SKILL.md", "x");
    assertEquals(resolver().resolve("skills/SKILL.md"), List.of("Library", "Framework"));
  }

  @Test
  public void symlinkedDirectoryFileIsRejectedRatherThanFollowed() {
    // BriefBuilder only ever walks the mapped top-level directories, so <root>/.mission-types is never visited and
    // its being a link is never tested by anything else in the pipeline. Publishing a link's target path as the
    // Mission Types of every file in the Brief would silently change which files each Handler installs.
    write(".mission-types", "../../etc/secrets");
    modes.put(".mission-types", TreeEntry.MODE_SYMLINK);
    write("rules/a.md", "x");

    assertThrows(BriefBuildException.class, () -> resolver().resolve("rules/a.md"));
  }

  @Test
  public void symlinkedSiblingFileIsRejectedRatherThanFollowed() {
    // The sibling is read by resolve() from inside addFile() while `a.md` is being processed, which is before the
    // sorted walk reaches `a.md.mission-types` -- so BriefBuilder's own rejection, which would otherwise catch this
    // entry, is defeated purely by iteration order.
    write("rules/a.md", "x");
    write("rules/a.md.mission-types", "../../elsewhere");
    modes.put("rules/a.md.mission-types", TreeEntry.MODE_SYMLINK);

    assertThrows(BriefBuildException.class, () -> resolver().resolve("rules/a.md"));
  }

  private MissionTypeResolver resolver() {
    return new MissionTypeResolver(new RepositoryContents("commit", files, modes));
  }

  private void write(String path, String content) {
    files.put(path, content.getBytes(StandardCharsets.UTF_8));
    modes.putIfAbsent(path, "100644");
  }
}
