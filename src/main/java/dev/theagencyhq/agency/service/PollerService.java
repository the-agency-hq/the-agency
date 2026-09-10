/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;

import org.lattejava.web.Configuration;

/**
 * The poll service: ask each registered source what its branch points at, and rebuild its Brief when the content
 * changes.
 *
 * <p>Runs every {@code poller.intervalSeconds} and wakes early on a {@link #nudge()} from the admin UI's
 * "Rebuild now" button. The nudge carries no payload, so a rebuild request runs the ordinary full cycle rather than a
 * second, subtly different code path for one Organization — the scheduled cycle already polls every source, so this
 * costs nothing a timer tick did not already cost.
 *
 * <p>Every kind of source is polled the same way, through the {@link RepositoryClient} for its host: an unchanged
 * source costs exactly one request per cycle, the commit lookup, and only a source whose branch has actually moved
 * pays for the tree and the archive. That ratio is what makes polling every Organization on a one-minute timer
 * affordable against a rate limit measured in thousands of requests an hour.
 *
 * <p>Sources are polled one after another on this single thread. That is what makes the whole service lock-free:
 * with no concurrent cycle and no second caller, one Organization can never build twice at once, and the
 * per-Organization lock map this class used to carry has nothing left to exclude.
 */
@Singleton
public class PollerService extends IntervalThread {
  public static final int MINIMUM_INTERVAL_SECONDS = 5;
  private static final System.Logger logger = System.getLogger(PollerService.class.getName());
  private final BriefRepository briefs;
  private final BriefBuilder builder;
  private final SourceCatalog catalog;
  private final boolean enabled;
  private final long interval;
  private final SourceLinkService links;
  private final OrganizationRepository organizations;
  private final BriefSourceRepository sources;

  /**
   * The application's poller, configured by {@code poller.intervalSeconds} and {@code poller.enabled}. The service
   * is always constructed, so every caller can nudge it unconditionally; only the thread behind it is optional. Off,
   * nothing polls and nothing rebuilds on its own -- which is what an instance that only serves the Briefing API
   * wants, and what the tests want so a background cycle can never race an assertion.
   */
  @Inject
  public PollerService(BriefRepository briefs, BriefSourceRepository sources, OrganizationRepository organizations,
                       SourceCatalog catalog, SourceLinkService links, BriefBuilder builder, Configuration config) {
    this(briefs, sources, organizations, catalog, links, builder, config.getInteger("poller.intervalSeconds", 60),
        config.getBoolean("poller.enabled", true));
  }

  /**
   * A poller that is never started on its own: the tests build one to start, nudge, and stop by hand.
   */
  public PollerService(BriefRepository briefs, BriefSourceRepository sources, OrganizationRepository organizations,
                       SourceCatalog catalog, SourceLinkService links, BriefBuilder builder, int intervalSeconds) {
    this(briefs, sources, organizations, catalog, links, builder, intervalSeconds, false);
  }

  private PollerService(BriefRepository briefs, BriefSourceRepository sources, OrganizationRepository organizations,
                        SourceCatalog catalog, SourceLinkService links, BriefBuilder builder, int intervalSeconds,
                        boolean enabled) {
    super("the-agency-poller");
    this.briefs = briefs;
    this.builder = builder;
    this.catalog = catalog;
    this.enabled = enabled;
    this.interval = Math.max(MINIMUM_INTERVAL_SECONDS, intervalSeconds);
    this.links = links;
    this.organizations = organizations;
    this.sources = sources;
  }

  /**
   * Stops the thread when the scope closes. Never interrupts, so the cycle in flight gets time to finish rather
   * than being torn apart between the build and the insert. {@code join()} on a thread that was never started
   * returns immediately.
   */
  @PreDestroy
  public void awaitShutdown() {
    shutdown();
    try {
      join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Starts the thread once the scope has built it, if the configuration enabled it.
   */
  @PostConstruct
  public void startIfEnabled() {
    if (enabled) {
      start();
    }
  }

  /**
   * One full cycle over every registered source.
   */
  @Override
  protected void execute() {
    var counts = new EnumMap<SourceStatus, Integer>(SourceStatus.class);
    for (var source : sources.findAll()) {
      // A source that has been authorized but not told what to poll yet -- an Organization between connecting a
      // host and picking a repository. There is nothing to ask about, and no status to record about nothing.
      if (!source.registered()) {
        continue;
      }

      SourceStatus status;
      try {
        status = poll(source);
      } catch (RuntimeException e) {
        // Reached only for a failure the branches below do not already record a status for. Every source is
        // independent, so this is logged and recorded rather than allowed to abort the rest of the cycle.
        logger.log(System.Logger.Level.ERROR, "Unable to poll source [" + source.source() + "]", e);
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

  private SourceStatus build(BriefSource source, RepositoryClient client, String accessToken, String head) {
    var organization = organizations.findById(source.organizationId()).orElse(null);
    if (organization == null) {
      var message = "The Organization [" + source.organizationId() + "] no longer exists";
      logger.log(System.Logger.Level.ERROR, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, message);
    }

    var config = source.config();
    String checksum;
    Brief built;
    try {
      built = builder.build(organization, client.contents(accessToken, config.fullName(), head));
      checksum = BriefBuilder.checksum(built);
    } catch (BriefBuildException e) {
      logger.log(System.Logger.Level.ERROR, "Unable to build the Brief for [" + organization.name() + "]", e);
      // The commit is deliberately not advanced, so the next cycle retries and a fixed repository recovers itself.
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage());
    } catch (RepositoryUnauthorizedException e) {
      // Ordered before the RepositoryException catch below, which it is a subtype of. The token was accepted
      // minutes ago by the head lookup and has been refused since, which means the authorization was revoked
      // mid-cycle.
      return notConnected(source, e);
    } catch (RepositoryException e) {
      // Split from the build failure above because it is a different person's problem: nothing is wrong with the
      // repository, so there is nothing for its author to fix and nothing for the operator to do but wait.
      logger.log(System.Logger.Level.ERROR, "Unable to download [" + config.fullName() + "]", e);
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, e.getMessage());
    }

    var latest = briefs.findLatestByOrganizationId(source.organizationId());
    if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
      // Identical content: advance the commit so the work is not repeated, but do NOT create a version. Without
      // this, an unrelated README commit would force every Handler on every machine to re-download.
      return record(source, head, SourceStatus.UNCHANGED, null);
    }

    // `head` is the Brief's sourceCommit -- the same commit that becomes the source's last_built_commit below.
    // The Brief goes in carrying everything but its version, which only the database can assign.
    var stored = briefs.create(
        new Brief(checksum, built.organization(), null, built.files(), head, Instant.now()));

    logger.log(System.Logger.Level.INFO, "Built Brief for [{0}] version [{1}]", organization.name(), stored.version());
    return record(source, head, SourceStatus.OK, null);
  }

  // The message is deliberately the same however the authorization turned out to be dead, because the instruction
  // is the same: reconnect. Which of the two paths got here is a detail for the log, not for the operator.
  private SourceStatus notConnected(BriefSource source, RuntimeException cause) {
    var label = source.type().label();
    logger.log(System.Logger.Level.WARNING, "No usable " + label + " authorization for [" + source.source() + "]", cause);

    // The credential is removed, not just reported: the admin UI reads a stored credential as a working
    // connection, so a token the host refused mid-cycle must not stay on the row. Idempotent, so the path where
    // accessToken() already removed it costs nothing.
    links.unlink(source.organizationId());
    return record(source, source.lastBuiltCommit(), SourceStatus.NOT_CONNECTED,
        "The " + label + " authorization for this source is no longer valid. Reconnect the Organization to restore it.");
  }

  private SourceStatus poll(BriefSource source) {
    var config = source.config();
    var label = config.type().label();

    // A source whose kind this server no longer has credentials for. Nothing is asked of the host, and above all
    // the stored credential is left alone: a refresh attempted without the application's secret would be refused
    // and the refusal read as a revocation, destroying a credential that a restored configuration would have used.
    if (!catalog.configured(config.type())) {
      var message = label + " is not configured on this server, so this source cannot be polled.";
      logger.log(System.Logger.Level.WARNING, message + " [" + source.source() + "]");
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, message);
    }

    // The token first, because everything else needs it and because it is the one failure the operator has to act
    // on. A source whose authorization has lapsed keeps serving its current Brief and says so on its detail page.
    // Two ways to find that out, and both land here: the stored credential is unusable before the call, or the
    // host refuses it during one -- a token that has not expired yet still stops working the moment it is revoked.
    var accessToken = links.accessToken(source.organizationId(), config);
    if (accessToken == null) {
      return notConnected(source, null);
    }

    var client = catalog.client(config.type());
    String head;
    try {
      head = client.head(accessToken, config.fullName(), config.branch());
    } catch (RepositoryUnauthorizedException e) {
      return notConnected(source, e);
    } catch (RepositoryException e) {
      logger.log(System.Logger.Level.WARNING, "Unable to resolve [" + config.fullName() + "]", e);
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, e.getMessage());
    }

    // A null head is the host declining to answer rather than failing: the branch is gone, the repository was
    // renamed or made private, or the account no longer reaches it. All of them need a human, and none of them is
    // something a retry fixes -- but the current Brief keeps serving regardless.
    if (head == null) {
      var message = label + " has no branch [" + config.branch() + "] in [" + config.fullName() + "], or the "
          + "repository is no longer visible to the connected account.";
      logger.log(System.Logger.Level.WARNING, message);
      return record(source, source.lastBuiltCommit(), SourceStatus.FETCH_FAILED, message);
    }

    if (head.equals(source.lastBuiltCommit()) && briefs.findLatestByOrganizationId(source.organizationId()).isPresent()) {
      return record(source, head, SourceStatus.UNCHANGED, null);
    }

    return build(source, client, accessToken, head);
  }

  private SourceStatus record(BriefSource source, String commit, SourceStatus status, String error) {
    // One instant for both columns: "last polled" and "last updated" describe the same event here, and reading the
    // clock twice would let them disagree for no reason.
    var now = Instant.now();

    // Written from a fresh read of the row rather than from the copy this cycle started with. Resolving the token
    // may have refreshed the credential on this very thread, and a whole-row write from the stale copy would put
    // the old credential back. A row the cascade has already removed -- the Organization was deleted mid-cycle --
    // is simply not there to write, which is the no-op the rest of the cycle depends on.
    sources.findByOrganizationId(source.organizationId())
           .ifPresent(current -> sources.update(current.withStatus(commit, now, status, error, now)));
    return status;
  }
}
