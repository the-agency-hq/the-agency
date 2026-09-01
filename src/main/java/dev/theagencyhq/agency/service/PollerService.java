/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The poll service: ask GitHub what each registered source's branch points at, and rebuild its Brief when the content
 * changes.
 *
 * <p>Runs every {@code poller.intervalSeconds} and wakes early on a {@link #nudge()} from the admin UI's
 * "Rebuild now" button. The nudge carries no payload, so a rebuild request runs the ordinary full cycle rather than a
 * second, subtly different code path for one Organization — the scheduled cycle already polls every source, so this
 * costs nothing a timer tick did not already cost.
 *
 * <p>An unchanged source costs exactly one GitHub request per cycle: the commit lookup. Only a source whose branch
 * has actually moved pays for the tree and the archive. That ratio is what makes polling every Organization on a
 * one-minute timer affordable against a rate limit measured in thousands of requests an hour.
 *
 * <p>Sources are polled one after another on this single thread. That is what makes the whole service lock-free:
 * with no concurrent cycle and no second caller, one Organization can never build twice at once, and the
 * per-Organization lock map this class used to carry has nothing left to exclude.
 */
public class PollerService extends IntervalThread {
  public static final int MINIMUM_INTERVAL_SECONDS = 5;
  private static final System.Logger logger = System.getLogger(PollerService.class.getName());
  private final BriefBuilder builder;
  private final DatabaseService database;
  private final GitHubClient github;
  private final long interval;
  private final GitHubLinkService links;

  public PollerService(DatabaseService database, GitHubClient github, GitHubLinkService links, BriefBuilder builder,
                       int intervalSeconds) {
    super("the-agency-poller");
    this.builder = builder;
    this.database = database;
    this.github = github;
    this.interval = Math.max(MINIMUM_INTERVAL_SECONDS, intervalSeconds);
    this.links = links;
  }

  /**
   * One full cycle over every registered source.
   */
  @Override
  protected void execute() {
    var counts = new EnumMap<SourceStatus, Integer>(SourceStatus.class);
    for (var source : database.listSources()) {
      SourceStatus status;
      try {
        status = poll(source);
      } catch (RuntimeException e) {
        // Reached only for a failure the branches below do not already record a status for. Every source is
        // independent, so this is logged and recorded rather than allowed to abort the rest of the cycle.
        logger.log(System.Logger.Level.ERROR, "Unable to poll source [" + source.fullName() + "]", e);
        status = record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, e.getMessage());
      }

      counts.merge(status, 1, Integer::sum);
    }

    if (!counts.isEmpty()) {
      logger.log(System.Logger.Level.INFO, "Poll cycle complete [{0}]", counts);
    }
  }

  @Override
  protected long intervalSeconds() {
    return interval;
  }

  private SourceStatus build(BriefSource source, String accessToken, String head) {
    var organization = database.findOrganization(source.organizationId()).orElse(null);
    if (organization == null) {
      var message = "The Organization [" + source.organizationId() + "] no longer exists";
      logger.log(System.Logger.Level.ERROR, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, message);
    }

    String checksum;
    Brief built;
    try {
      built = builder.build(organization, github.contents(accessToken, source.owner(), source.repository(), head));
      checksum = BriefBuilder.checksum(built);
    } catch (BriefBuildException e) {
      logger.log(System.Logger.Level.ERROR, "Unable to build the Brief for [" + organization.name() + "]", e);
      // The commit is deliberately not advanced, so the next cycle retries and a fixed repository recovers itself.
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage());
    } catch (GitHubUnauthorizedException e) {
      // Ordered before the GitHubException catch below, which it is a subtype of. The token was accepted minutes
      // ago by the head lookup and has been refused since, which means the authorization was revoked mid-cycle.
      return notConnected(source, e);
    } catch (GitHubException e) {
      // Split from the build failure above because it is a different person's problem: nothing is wrong with the
      // repository, so there is nothing for its author to fix and nothing for the operator to do but wait.
      logger.log(System.Logger.Level.ERROR, "Unable to download [" + source.fullName() + "]", e);
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, e.getMessage());
    }

    var latest = database.findLatestBrief(source.organizationId());
    if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
      // Identical content: advance the commit so the work is not repeated, but do NOT create a version. Without
      // this, an unrelated README commit would force every Handler on every machine to re-download.
      return record(source, head, SourceStatus.UNCHANGED, null);
    }

    // `head` is the Brief's sourceCommit -- the same commit that becomes the source's last_built_commit below.
    // The Brief goes in carrying everything but its version, which only the database can assign.
    var stored = database.insertBrief(
        new Brief(checksum, built.organization(), null, built.files(), head, Instant.now()));

    logger.log(System.Logger.Level.INFO, "Built Brief for [{0}] version [{1}]", organization.name(), stored.version());
    return record(source, head, SourceStatus.OK, null);
  }

  // The message is deliberately the same however the authorization turned out to be dead, because the instruction
  // is the same: reconnect. Which of the two paths got here is a detail for the log, not for the operator.
  private SourceStatus notConnected(BriefSource source, RuntimeException cause) {
    logger.log(System.Logger.Level.WARNING, "No usable GitHub authorization for [" + source.fullName() + "]", cause);

    // The credential is removed, not just reported: the admin UI reads a stored credential as a working
    // connection, so a token GitHub refused mid-cycle must not stay on the row. Idempotent, so the path where
    // accessToken() already removed it costs nothing.
    links.unlink(source.organizationId());
    return record(source, source.lastBuiltCommit(), SourceStatus.NOT_CONNECTED,
        "The GitHub authorization for this source is no longer valid. Reconnect the Organization to restore it.");
  }

  private SourceStatus poll(BriefSource source) {
    // The token first, because everything else needs it and because it is the one failure the operator has to act
    // on. A source whose authorization has lapsed keeps serving its current Brief and says so on its detail page.
    // Two ways to find that out, and both land here: the stored credential is unusable before the call, or GitHub
    // refuses it during one -- a token that has not expired yet still stops working the moment it is revoked.
    var accessToken = links.accessToken(source.organizationId());
    if (accessToken == null) {
      return notConnected(source, null);
    }

    String head;
    try {
      head = github.head(accessToken, source.owner(), source.repository(), source.branch());
    } catch (GitHubUnauthorizedException e) {
      return notConnected(source, e);
    } catch (GitHubException e) {
      logger.log(System.Logger.Level.WARNING, "Unable to resolve [" + source.fullName() + "]", e);
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, e.getMessage());
    }

    // A null head is GitHub declining to answer rather than failing: the branch is gone, the repository was
    // renamed or made private, or the App's installation no longer covers it. All of them need a human, and none
    // of them is something a retry fixes -- but the current Brief keeps serving regardless.
    if (head == null) {
      var message = "GitHub has no branch [" + source.branch() + "] in [" + source.fullName() + "], or the "
          + "repository is no longer visible to the connected account.";
      logger.log(System.Logger.Level.WARNING, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, message);
    }

    if (head.equals(source.lastBuiltCommit()) && database.findLatestBrief(source.organizationId()).isPresent()) {
      return record(source, head, SourceStatus.UNCHANGED, null);
    }

    return build(source, accessToken, head);
  }

  private SourceStatus record(BriefSource source, String commit, SourceStatus status, String error) {
    // One instant for both columns: "last polled" and "last updated" describe the same event here, and reading the
    // clock twice would let them disagree for no reason.
    var now = Instant.now();
    database.updateSourceStatus(source.organizationId(), commit, now, status, error, now);
    return status;
  }
}
