/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.web;

import dev.theagencyhq.agency.db.DatabaseService;
import org.lattejava.web.Configuration;

/**
 * A simple service registry. Every service is a singleton created in {@link #initialize(Configuration)}.
 */
public class Services {
  private static BriefBuilder briefBuilder;
  private static BriefingService briefingService;
  private static DatabaseService databaseService;
  private static GitService gitService;
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

  public static GitService gitService() {
    return gitService;
  }

  public static void initialize(Configuration config) {
    shutdownStarted.set(false);

    // The database service owns the pool and must exist before anything that uses it.
    databaseService = new DatabaseService(config);
    briefBuilder = new BriefBuilder();
    briefingService = new BriefingService(databaseService);
    gitService = new GitService();
    organizationService = new OrganizationService(databaseService, gitService);
    pollerService = new PollerService(databaseService, gitService, briefBuilder,
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
