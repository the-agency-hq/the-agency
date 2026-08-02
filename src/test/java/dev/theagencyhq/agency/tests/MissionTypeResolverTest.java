/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.service.BriefBuildException;
import dev.theagencyhq.agency.service.MissionTypeResolver;
import java.nio.file.Files;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

@Test
public class MissionTypeResolverTest {
  private Path root;

  @AfterMethod
  public void afterMethod() throws IOException {
    if (root != null) {
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    root = Files.createDirectories(Path.of("build/test/mission-types-" + UUID.randomUUID()));
  }

  @Test
  public void blankLinesAndDuplicatesAreDropped() throws Exception {
    write("skills/.mission-types", "Web\n\n  \nWeb\nLibrary\n");
    var file = write("skills/a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web", "Library"));
  }

  @Test
  public void directoryFileAppliesToSubdirectories() throws Exception {
    write("skills/.mission-types", "Web\n");
    var file = write("skills/skill1/scripts/run.sh", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web"));
  }

  @Test
  public void fileDirectlyInSourceRootUsesRootDirectoryFile() throws Exception {
    write(".mission-types", "Web\n");
    var file = write("a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web"));
  }

  @Test
  public void nearerDirectoryFileWins() throws Exception {
    write("skills/.mission-types", "Web\n");
    write("skills/skill1/.mission-types", "Library\n");
    var file = write("skills/skill1/SKILL.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Library"));
  }

  @Test
  public void noFileMeansEveryMissionType() throws Exception {
    var file = write("rules/a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of());
  }

  @Test
  public void originalCaseIsPreserved() throws Exception {
    write("rules/.mission-types", "Web\nLIBRARY\n");
    var file = write("rules/a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web", "LIBRARY"));
  }

  @Test
  public void siblingFileBeatsDirectoryFile() throws Exception {
    write("skills/.mission-types", "Web\n");
    write("skills/SKILL.md.mission-types", "Library\nFramework\n");
    var file = write("skills/SKILL.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Library", "Framework"));
  }

  @Test
  public void symlinkedDirectoryFileIsRejectedRatherThanFollowed() throws Exception {
    // BriefBuilder.collect only ever walks the five mapped top-level directories, so <root>/.mission-types is never
    // visited and its symlink-ness is never tested by anything else in the pipeline. Following it would read an
    // arbitrary local file and publish its lines as the Mission Types of every file in the Brief.
    var target = write("outside.txt", "Internal\nSecret\n");
    Files.createSymbolicLink(root.resolve(".mission-types"), target);
    var file = write("rules/a.md", "x");

    assertThrows(BriefBuildException.class, () -> new MissionTypeResolver(root).resolve(file));
  }

  @Test
  public void symlinkedSiblingFileIsRejectedRatherThanFollowed() throws Exception {
    // The sibling is read by resolve() from inside addFile() while `a.md` is being processed, which happens before
    // the sorted directory listing reaches `a.md.mission-types` -- so BriefBuilder's own symlink rejection, which
    // would otherwise catch this entry, is defeated purely by iteration order.
    var target = write("outside.txt", "Internal\n");
    var file = write("rules/a.md", "x");
    Files.createSymbolicLink(root.resolve("rules/a.md.mission-types"), target);

    assertThrows(BriefBuildException.class, () -> new MissionTypeResolver(root).resolve(file));
  }

  @Test
  public void walkDoesNotEscapeAboveSourceRoot() throws Exception {
    write(".mission-types", "Web\n");
    var sourceRoot = Files.createDirectories(root.resolve("skills"));
    var file = write("skills/a.md", "x");
    assertEquals(new MissionTypeResolver(sourceRoot).resolve(file), List.of());
  }

  private Path write(String relative, String content) throws IOException {
    var path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }
}
