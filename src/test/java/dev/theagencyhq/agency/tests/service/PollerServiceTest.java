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

import static org.testng.Assert.*;

@SuppressWarnings("BusyWait")
@Test
public class PollerServiceTest extends BaseTest {
  private Path root;

  // BaseTest empties the database before every method, so only the temporary Git work tree is this class's to
  // remove. alwaysRun = true so a failure partway through beforeMethod still cleans up the directory it created.
  @AfterMethod(alwaysRun = true)
  public void afterMethod() throws IOException {
    deleteDirectory(root);
    root = null;
  }

  @Test
  public void buildFailureDoesNotAdvanceTheCommit() throws Exception {
    var organization = organizationService.create("poller-" + UUID.randomUUID(), root.toString());
    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    Files.delete(root.resolve("the-agency-hq-settings.json"));
    commit(root, "break it");

    assertEquals(runCycle(organization.id()), SourceStatus.BUILD_FAILED);
    var after = db.findSource(organization.id()).orElseThrow();
    assertTrue(after.lastError() != null && !after.lastError().isBlank());

    // The previous version is still live and serving
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 1);
  }

  @Test
  public void buildsThenSkipsThenVersionsOnlyOnContentChange() throws Exception {
    var organization = organizationService.create("poller-" + UUID.randomUUID(), root.toString());
    // First cycle builds version 1
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 1);

    // Same commit -> no work at all
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);

    // A new commit that does not change the Brief's content -> new commit, but NO new version
    Files.writeString(root.resolve("README.md"), "unrelated\n");
    commit(root, "readme");
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 1);

    // A commit that does change the content -> version 2
    Files.writeString(root.resolve("rules/a.md"), "second\n");
    commit(root, "rule change");
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 2);
  }

  // Runs after BaseTest's reset -- TestNG invokes a superclass @BeforeMethod first. Named differently on purpose:
  // overriding beforeMethod() is impossible here, since an override cannot add a checked exception.
  @BeforeMethod
  public void createSourceRepository() throws Exception {
    root = Files.createDirectories(Path.of("build/test/poller-" + UUID.randomUUID()).toAbsolutePath());
    Files.writeString(root.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    Files.createDirectories(root.resolve("rules"));
    Files.writeString(root.resolve("rules/a.md"), "first\n");
    initRepository(root);
  }

  @Test
  public void failedPullStillBuilds() {
    // This repository has no remote at all, so `git pull` always fails. The build must happen anyway.
    var organization = organizationService.create("poller-" + UUID.randomUUID(), root.toString());
    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    var source = db.findSource(organization.id()).orElseThrow();
    assertTrue(source.lastPullError() != null && !source.lastPullError().isBlank());
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 1);
  }

  @Test
  public void nudgeWakesTheThreadWithoutWaitingOutTheInterval() throws Exception {
    var organization = organizationService.create("poller-" + UUID.randomUUID(), root.toString());

    // An hour-long interval, so nothing but the nudge can account for a cycle running. This is the only poller
    // thread the suite ever starts (poller.enabled=false switches off the one Services wires up), and it is joined
    // before this method returns so a trailing cycle can never touch another test class's rows.
    var thread = new PollerService(db, new GitService(), new BriefBuilder(), 3600);
    thread.start();
    try {
      thread.nudge();

      var deadline = Instant.now().plusSeconds(30);
      while (db.findLatestBrief(organization.id()).isEmpty() && Instant.now().isBefore(deadline)) {
        Thread.sleep(50);
      }
    } finally {
      thread.shutdown();
      thread.join(30_000);
    }

    assertFalse(thread.isAlive(), "The poller thread did not stop after shutdown()");
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 1);
  }

  @Test
  public void unbornHEADYieldsNotARepository() throws Exception {
    // A repository that has been `git init`'d but never committed to has an "unborn" HEAD: `git rev-parse
    // --is-inside-work-tree` still prints `true` (verified empirically against this machine's git before writing
    // this test), so isWorkTree() passes and the branch actually reached is "unable to read HEAD", not
    // NOT_A_REPOSITORY-via-isWorkTree. Both branches report the same SourceStatus, so this pins down which one.
    var unbornRoot = Files.createDirectories(Path.of("build/test/poller-unborn-" + UUID.randomUUID())
                                                 .toAbsolutePath());
    try {
      Files.writeString(unbornRoot.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
      run(unbornRoot, "git", "init", "-q", "-b", "main");
      run(unbornRoot, "git", "config", "user.email", "test@theagencyhq.dev");
      run(unbornRoot, "git", "config", "user.name", "Test");
      run(unbornRoot, "git", "config", "commit.gpgsign", "false");

      var organization = organizationService.create("poller-" + UUID.randomUUID(), unbornRoot.toString());

      assertEquals(runCycle(organization.id()), SourceStatus.NOT_A_REPOSITORY);
      var after = db.findSource(organization.id()).orElseThrow();
      assertTrue(after.lastError() != null && after.lastError().contains(unbornRoot.toString()), after.lastError());
    } finally {
      deleteDirectory(unbornRoot);
    }
  }

  /**
   * Runs one real cycle and reports the status it recorded for one Organization. The status is read back off the
   * {@code brief_sources} row rather than returned by the call, which is the stronger assertion: it proves the status
   * was persisted, not merely computed. BaseTest empties the database before each method, so the cycle only ever has
   * this method's own source to poll.
   */
  private SourceStatus runCycle(UUID organizationId) {
    pollerService.testRun();
    return db.findSource(organizationId).orElseThrow().lastStatus();
  }
}
