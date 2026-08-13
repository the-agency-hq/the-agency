/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.web;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.github.GitHubClient;
import dev.theagencyhq.agency.github.GitHubHTTPClient;
import org.lattejava.web.Configuration;

/**
 * A simple service registry. Every service is a singleton created in
 * {@link #initialize(Configuration, GitHubClient)}.
 */
public class Services {
  private static BriefBuilder briefBuilder;
  private static BriefingService briefingService;
  private static DatabaseService databaseService;
  private static GitHubClient gitHubClient;
  private static GitHubLinkService gitHubLinkService;
  private static OrganizationService organizationService;
  private static PollerService pollerService;
  private static final AtomicBoolean shutdownStarted = new AtomicBoolean();

  public static BriefBuilder briefBuilder() {
    return briefBuilder;
  }

  public static BriefingService briefingService() {
    return briefingService;
  }

  public static DatabaseService databaseService() {
    return databaseService;
  }

  public static GitHubClient gitHubClient() {
    return gitHubClient;
  }

  public static GitHubLinkService gitHubLinkService() {
    return gitHubLinkService;
  }

  /**
   * @param config The configuration.
   * @param github The GitHub client every service that talks to GitHub is built on, or {@code null} to build the
   *               real one from {@code github.clientId} and {@code github.clientSecret}. Taken as a parameter for
   *               one reason: it is the Agency's only outbound dependency on a service it does not own, and a test
   *               suite that reached the real api.github.com would need a live GitHub App, a live installation, and
   *               a network — and would still be measuring GitHub rather than the Agency.
   */
  public static void initialize(Configuration config, GitHubClient github) {
    shutdownStarted.set(false);

    // The database service owns the pool and must exist before anything that uses it.
    databaseService = new DatabaseService(config);
    gitHubClient = github != null ? github
        : new GitHubHTTPClient(config.get("github.clientId"), config.get("github.clientSecret"));
    briefBuilder = new BriefBuilder();
    briefingService = new BriefingService(databaseService);
    gitHubLinkService = new GitHubLinkService(config, databaseService, gitHubClient);
    organizationService = new OrganizationService(databaseService, gitHubClient);
    pollerService = new PollerService(databaseService, gitHubClient, gitHubLinkService, briefBuilder,
        config.getInteger("poller.intervalSeconds", 60));

    // The service is always constructed, so every caller can nudge it unconditionally; only the thread behind it is
    // optional. Off, nothing polls and nothing rebuilds on its own -- which is what an instance that only serves the
    // Briefing API wants, and what the tests want so a background cycle can never race an assertion.
    if (config.getBoolean("poller.enabled", true)) {
      pollerService.start();
    }
  }

  public static OrganizationService organizationService() {
    return organizationService;
  }

  public static PollerService pollerService() {
    return pollerService;
  }

  /**
   * Idempotent and safe to call from more than one thread at once, because it genuinely is called that way.
   * {@code Main} registers this with {@code Web.addShutdownTask}, and Web runs its shutdown tasks from
   * {@code closeServer()}, which is reached both from {@code Web.close()} and from the JVM shutdown hook Web
   * installs for itself. {@code Main.close()} calls this directly as well, and the tests add a second JVM hook of
   * their own — at JVM exit those hooks run concurrently, so a plain null check would not be enough.
   */
  public static void shutdown() {
    if (!shutdownStarted.compareAndSet(false, true)) {
      return;
    }

    if (pollerService != null) {
      // Never interrupts, so give the cycle in flight time to finish rather than tearing it apart between the
      // build and the insert. join() on a thread that was never started returns immediately.
      pollerService.shutdown();
      try {
        pollerService.join(10_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (databaseService != null) {
      databaseService.close();
    }
  }
}
