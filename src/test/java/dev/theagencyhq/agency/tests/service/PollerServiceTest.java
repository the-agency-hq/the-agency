/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.github.GitHubException;
import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.service.*;
import dev.theagencyhq.agency.tests.*;
import dev.theagencyhq.agency.tests.github.FakeGitHubClient;

import static org.testng.Assert.*;

@SuppressWarnings("BusyWait")
@Test
public class PollerServiceTest extends BaseTest {
  private Organization organization;
  private FakeGitHubClient.Repository repository;

  /**
   * An access token that has expired is refreshed in place rather than reported as a failure, and the refreshed
   * credential is written back to the database — so the cycle after it does not refresh again. This is the one
   * behaviour that makes unattended polling possible at all: GitHub's user-to-server tokens last eight hours and
   * the poller runs for months.
   */
  @Test
  public void anExpiredAccessTokenIsRefreshedInPlace() {
    // Replace the credential stored in setup with one whose access token is already past its expiry, and do it
    // without reading the token back -- accessToken() would refresh it here, which is the very thing under test.
    github.tokenLifetime(Duration.ofSeconds(-1));
    assertEquals(links.link(organization.id(), "code", "http://localhost/callback"),
        GitHubLinkService.LinkResult.LINKED);
    github.tokenLifetime(Duration.ofHours(8));

    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);

    // The refreshed credential was stored, not merely used once: a further cycle needs no refresh at all, which is
    // proven by making refresh fail outright and watching the cycle succeed anyway.
    github.failRefresh(true);
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);
  }

  /**
   * The whole point of resolving the ref before downloading anything. An unchanged source costs exactly one GitHub
   * request per cycle, which is what makes polling every Organization on a one-minute timer affordable against a
   * rate limit; a cycle that downloaded first and compared afterwards would cost a full archive per Organization
   * per minute forever.
   */
  @Test
  public void anUnchangedSourceNeverDownloadsTheRepository() {
    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    // Only the download fails from here; the commit lookup still works. So a cycle that reports UNCHANGED can only
    // have done so without reaching for the archive.
    github.failContents(new GitHubException("The archive must not be downloaded for an unchanged source"));
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);

    // And the same failure is genuinely reachable, which is what stops the assertion above from being vacuous:
    // move the branch and the very next cycle does download, and fails.
    repository.putFile("rules/a.md", "second\n");
    assertEquals(runCycle(organization.id()), SourceStatus.FETCH_FAILED);
  }

  @Test
  public void aRevokedAuthorizationIsNotConnectedAndKeepsServing() {
    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    // A token that has not expired yet, and that GitHub has stopped honouring. The Agency cannot know until it
    // tries, so this is the path where a 401 mid-cycle is what tells it.
    github.revokeAll();

    assertEquals(runCycle(organization.id()), SourceStatus.NOT_CONNECTED);
    var after = db.findSource(organization.id()).orElseThrow();
    assertTrue(after.lastError().contains("Reconnect"), after.lastError());
    // Nothing is torn down: the Brief that was published stays published, because a lapsed authorization says
    // nothing about whether the content it produced is still correct.
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);
  }

  @Test
  public void aTransportFailureIsFetchFailedAndRecovers() {
    github.fail(new GitHubException("api.github.com is unreachable"));

    assertEquals(runCycle(organization.id()), SourceStatus.FETCH_FAILED);
    assertTrue(db.findSource(organization.id()).orElseThrow().lastError().contains("unreachable"));

    // Nothing about this is permanent, so the next cycle simply works.
    github.fail(null);
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);
  }

  /**
   * The branch disappearing is neither a transport failure nor a build failure: GitHub answered, and the answer was
   * that there is nothing there. It needs a human, and until one arrives the current Brief keeps serving.
   */
  @Test
  public void aVanishedBranchIsFetchFailedAndKeepsServing() {
    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    repository.removeBranch("main");

    assertEquals(runCycle(organization.id()), SourceStatus.FETCH_FAILED);
    assertTrue(db.findSource(organization.id()).orElseThrow().lastError().contains("main"));
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);
  }

  @Test
  public void buildFailureDoesNotAdvanceTheCommit() {
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    var builtCommit = db.findSource(organization.id()).orElseThrow().lastBuiltCommit();

    repository.removeFile("the-agency-hq-settings.json");

    assertEquals(runCycle(organization.id()), SourceStatus.BUILD_FAILED);
    var after = db.findSource(organization.id()).orElseThrow();
    assertTrue(after.lastError() != null && !after.lastError().isBlank());
    // Deliberately still the commit that last built, so the next cycle retries and a fixed repository recovers on
    // its own rather than needing someone to notice.
    assertEquals(after.lastBuiltCommit(), builtCommit);

    // The previous version is still live and serving.
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);
  }

  @Test
  public void buildsThenSkipsThenVersionsOnlyOnContentChange() {
    // First cycle builds version 1.
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);

    // Same commit -> no work at all.
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);

    // A new commit that does not change the Brief's content -> new commit, but NO new version. README.md is not a
    // mapped path, so it moves the head without changing a single Brief file.
    repository.putFile("README.md", "unrelated\n");
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);

    // A commit that does change the content -> version 2.
    repository.putFile("rules/a.md", "second\n");
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 2);
  }

  // Runs after BaseTest's reset -- TestNG invokes a superclass @BeforeMethod first -- so this builds on an empty
  // database and an empty fake. Named apart from beforeMethod, which it must not override.
  @BeforeMethod
  public void createSource() {
    repository = github.add("acme", "briefs").putFile("rules/a.md", "first\n");
    organization = organizationService.create("poller-" + UUID.randomUUID());
    linkGitHub(organization.id());
    connect(organization.id(), "acme", "briefs");
  }

  @Test
  public void nudgeWakesTheThreadWithoutWaitingOutTheInterval() throws Exception {
    // An hour-long interval, so nothing but the nudge can account for a cycle running. This is the only poller
    // thread the suite ever starts (poller.enabled=false switches off the one Services wires up), and it is joined
    // before this method returns so a trailing cycle can never touch another test class's rows.
    var thread = new PollerService(db, github, links, new BriefBuilder(), 3600);
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
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 1);
  }
}
