/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.Brief;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.SourceStatus;

/**
 * The poll service: pull each registered source and rebuild its Brief when the content changes.
 *
 * <p>Runs every {@code poller.intervalSeconds} and wakes early on a {@link #nudge()} from the admin UI's
 * "Rebuild now" button. The nudge carries no payload, so a rebuild request runs the ordinary full cycle rather
 * than a second, subtly different code path for one Organization — the scheduled cycle already pulls every source,
 * so this costs nothing a timer tick did not already cost.
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
  private final GitService git;
  private final long interval;

  public PollerService(DatabaseService database, GitService git, BriefBuilder builder, int intervalSeconds) {
    super("the-agency-poller");
    this.builder = builder;
    this.database = database;
    this.git = git;
    this.interval = Math.max(MINIMUM_INTERVAL_SECONDS, intervalSeconds);
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
        // Reached only for a failure before the pull has run or one unrelated to it -- everything downstream of the
        // pull records its own status with the pull error in hand. Every source is independent, so this is logged
        // and recorded rather than allowed to abort the rest of the cycle.
        logger.log(System.Logger.Level.ERROR, "Unable to poll source [" + source.path() + "]", e);
        status = record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage(), null);
      }

      counts.merge(status, 1, Integer::sum);
    }

    logger.log(System.Logger.Level.INFO, "Poll cycle complete [" + counts + "]");
  }

  @Override
  protected long intervalSeconds() {
    return interval;
  }

  private SourceStatus build(BriefSource source, String head, String pullError) {
    var organization = database.findOrganization(source.organizationId()).orElse(null);
    if (organization == null) {
      var message = "The Organization [" + source.organizationId() + "] no longer exists";
      logger.log(System.Logger.Level.ERROR, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, message, pullError);
    }

    String checksum;
    Brief built;
    try {
      built = builder.build(organization, Path.of(source.path()));
      checksum = BriefBuilder.checksum(built);
    } catch (BriefBuildException e) {
      logger.log(System.Logger.Level.ERROR, "Unable to build the Brief for [" + organization.name() + "]", e);
      // The commit is deliberately not advanced, so the next cycle retries and a fixed repository recovers itself.
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage(), pullError);
    }

    var latest = database.findLatestBrief(source.organizationId());
    if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
      // Identical content: advance the commit so the work is not repeated, but do NOT create a version. Without
      // this, an unrelated README commit would force every Handler on every machine to re-download.
      return record(source, head, SourceStatus.UNCHANGED, null, pullError);
    }

    // `head` is the Brief's sourceCommit -- the same commit that becomes the source's last_built_commit below.
    // The Brief goes in carrying everything but its version, which only the database can assign.
    var stored = database.insertBrief(
        new Brief(checksum, built.organization(), null, built.files(), head, Instant.now()));

    logger.log(System.Logger.Level.INFO,
        "Built Brief for [" + organization.name() + "] version [" + stored.version() + "]");
    return record(source, head, SourceStatus.OK, null, pullError);
  }

  private SourceStatus poll(BriefSource source) {
    var path = Path.of(source.path());
    if (!git.isWorkTree(path)) {
      var message = "The path [" + source.path() + "] is missing or is not a Git repository";
      logger.log(System.Logger.Level.ERROR, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.NOT_A_REPOSITORY, message, null);
    }

    // A failed pull is never fatal: it is what lets a purely local repository with no remote work unchanged.
    var pull = git.pull(path);
    var pullError = pull.success() ? null : pull.output().trim();
    if (pullError != null) {
      logger.log(System.Logger.Level.WARNING, "Unable to pull [" + source.path() + "]: [" + pullError + "]");
    }

    var head = git.head(path).orElse(null);
    if (head == null) {
      var message = "Unable to read HEAD in [" + source.path() + "]";
      logger.log(System.Logger.Level.ERROR, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.NOT_A_REPOSITORY, message, pullError);
    }

    if (head.equals(source.lastBuiltCommit()) && database.findLatestBrief(source.organizationId()).isPresent()) {
      return record(source, head, SourceStatus.UNCHANGED, null, pullError);
    }

    // Split out so the pull error is a plain local here rather than something an outer catch has to smuggle back
    // out of the failing call. Everything from this point on records its own status and carries it along.
    try {
      return build(source, head, pullError);
    } catch (RuntimeException e) {
      logger.log(System.Logger.Level.ERROR, "Unable to build source [" + source.path() + "]", e);
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage(), pullError);
    }
  }

  private SourceStatus record(BriefSource source, String commit, SourceStatus status, String error, String pullError) {
    // One instant for both columns: "last polled" and "last updated" describe the same event here, and reading the
    // clock twice would let them disagree for no reason.
    var now = Instant.now();
    database.updateSourceStatus(source.organizationId(), commit, now, status, error, pullError, now);
    return status;
  }
}
