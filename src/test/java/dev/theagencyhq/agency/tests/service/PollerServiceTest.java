/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.tests.*;
import dev.theagencyhq.agency.tests.source.FakeRepositoryClient;
// A single-type import because TestNG exports a Configuration of its own.
import org.lattejava.web.Configuration;

import static org.testng.Assert.*;

@SuppressWarnings("BusyWait")
@Test
public class PollerServiceTest extends BaseTest {
  private Organization organization;
  private FakeRepositoryClient.Repository repository;

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
    assertEquals(links.link(BriefSourceType.GITHUB, organization.id(), "code", "http://localhost/callback"),
        SourceLinkService.LinkResult.LINKED);
    github.tokenLifetime(Duration.ofHours(8));

    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);

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
    github.failContents(new RepositoryException("The archive must not be downloaded for an unchanged source"));
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
    var after = sources.findByOrganizationId(organization.id()).orElseThrow();
    assertTrue(after.lastError().contains("Reconnect"), after.lastError());
    // The dead credential is removed, not just reported, so the Organization's pages stop reading as connected
    // and offer the reconnect. The repository stays: reconnecting is all the operator has to do.
    assertNull(connection(organization.id()));
    var config = (GitHubConfig) after.config();
    assertEquals(config.fullName(), "acme/briefs");
    assertEquals(config.branch(), "main");
    // Nothing is torn down: the Brief that was published stays published, because a lapsed authorization says
    // nothing about whether the content it produced is still correct.
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);
  }

  @Test
  public void aTransportFailureIsFetchFailedAndRecovers() {
    github.fail(new RepositoryException("api.github.com is unreachable"));

    assertEquals(runCycle(organization.id()), SourceStatus.FETCH_FAILED);
    assertTrue(sources.findByOrganizationId(organization.id()).orElseThrow().lastError().contains("unreachable"));

    // Nothing about this is permanent, so the next cycle simply works.
    github.fail(null);
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);
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
    assertTrue(sources.findByOrganizationId(organization.id()).orElseThrow().lastError().contains("main"));
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);
  }

  @Test
  public void buildFailureDoesNotAdvanceTheCommit() {
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    var builtCommit = sources.findByOrganizationId(organization.id()).orElseThrow().lastBuiltCommit();

    repository.removeFile("the-agency-hq-settings.json");

    assertEquals(runCycle(organization.id()), SourceStatus.BUILD_FAILED);
    var after = sources.findByOrganizationId(organization.id()).orElseThrow();
    assertTrue(after.lastError() != null && !after.lastError().isBlank());
    // Deliberately still the commit that last built, so the next cycle retries and a fixed repository recovers on
    // its own rather than needing someone to notice.
    assertEquals(after.lastBuiltCommit(), builtCommit);

    // The previous version is still live and serving.
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);
  }

  @Test
  public void buildsThenSkipsThenVersionsOnlyOnContentChange() {
    // First cycle builds version 1.
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);

    // Same commit -> no work at all.
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);

    // A new commit that does not change the Brief's content -> new commit, but NO new version. README.md is not a
    // mapped path, so it moves the head without changing a single Brief file.
    repository.putFile("README.md", "unrelated\n");
    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);

    // A commit that does change the content -> version 2.
    repository.putFile("rules/a.md", "second\n");
    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 2);
  }

  /**
   * A source that has been authorized but not pointed at a repository is nothing to poll: the cycle passes over it
   * without recording a status, rather than reporting a failure about a repository nobody has named.
   */
  @Test
  public void aConnectedButUnregisteredSourceIsSkipped() {
    var pending = organizationService.create("poller-pending-" + UUID.randomUUID(), testUser);
    linkGitHub(pending.id());

    pollerService.testRun();

    var source = sources.findByOrganizationId(pending.id()).orElseThrow();
    assertTrue(source.connected());
    assertFalse(source.registered());
    assertNull(source.lastStatus());
    assertNull(source.lastPolledInstant());
    // The registered Organization from setup was still polled in the same cycle.
    assertEquals(sources.findByOrganizationId(organization.id()).orElseThrow().lastStatus(), SourceStatus.OK);
  }

  // Runs after BaseTest's reset -- TestNG invokes a superclass @BeforeMethod first -- so this builds on an empty
  // database and an empty fake. Named apart from beforeMethod, which it must not override.
  @BeforeMethod
  public void createSource() {
    repository = github.add("acme", "briefs").putFile("rules/a.md", "first\n");
    organization = organizationService.create("poller-" + UUID.randomUUID(), testUser);
    linkGitHub(organization.id());
    connect(organization.id(), "acme/briefs");
  }

  /**
   * A source whose kind this server holds no credentials for -- GitHub connected once, then its configuration
   * removed -- is reported rather than polled, and above all its credential is left alone. A refresh attempted
   * without the application's secret would be refused, and the refusal read as a revocation would strip a
   * credential that a restored configuration would have gone on using.
   */
  @Test
  public void aSourceOfAnUnconfiguredKindIsReportedNotPolled() throws Exception {
    // A catalog built from an empty configuration: no kind is configured, though the fakes are still the clients.
    var unconfigured = new PollerService(briefs, sources, organizations,
        new SourceCatalog(new Configuration(List.of()), github, gitlab), links, new BriefBuilder(), 3600);
    github.tokenLifetime(Duration.ofSeconds(-1));
    assertEquals(links.link(BriefSourceType.GITHUB, organization.id(), "code", "http://localhost/callback"),
        SourceLinkService.LinkResult.LINKED);
    var stored = connection(organization.id()).tokens().accessToken();

    unconfigured.testRun();

    var after = sources.findByOrganizationId(organization.id()).orElseThrow();
    assertEquals(after.lastStatus(), SourceStatus.FETCH_FAILED);
    assertTrue(after.lastError().contains("GitHub is not configured"), after.lastError());
    // The expired credential is still there, unrefreshed and unremoved.
    assertEquals(connection(organization.id()).tokens().accessToken(), stored);
    assertTrue(briefs.findLatestByOrganizationId(organization.id()).isEmpty());
  }

  @Test
  public void nudgeWakesTheThreadWithoutWaitingOutTheInterval() throws Exception {
    // An hour-long interval, so nothing but the nudge can account for a cycle running. This is the only poller
    // thread the suite ever starts (poller.enabled=false keeps the scope's own from starting), and it is joined
    // before this method returns so a trailing cycle can never touch another test class's rows.
    var thread = new PollerService(briefs, sources, organizations, catalog, links, new BriefBuilder(), 3600);
    thread.start();
    try {
      thread.nudge();

      var deadline = Instant.now().plusSeconds(30);
      while (briefs.findLatestByOrganizationId(organization.id()).isEmpty() && Instant.now().isBefore(deadline)) {
        Thread.sleep(50);
      }
    } finally {
      thread.shutdown();
      thread.join(30_000);
    }

    assertFalse(thread.isAlive(), "The poller thread did not stop after shutdown()");
    assertEquals(briefs.findLatestByOrganizationId(organization.id()).orElseThrow().version().intValue(), 1);
  }
}
