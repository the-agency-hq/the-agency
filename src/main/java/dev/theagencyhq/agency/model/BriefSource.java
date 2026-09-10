/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The source a Brief is built from. Exactly one per Organization, of exactly one {@link BriefSourceType}, whose
 * whole configuration — credential and all — is the {@link #config()} document.
 *
 * <p>The row exists from the moment its kind is authorized, before it has been told what to poll: an OAuth
 * callback creates a connected, unregistered GitHub source, and the repository picker registers it afterwards. The
 * poller polls only a source that is {@link #registered()}; one whose credential has lapsed goes
 * {@link SourceStatus#NOT_CONNECTED} and stays there until someone reconnects it, which is the honest outcome:
 * nobody is entitled to that repository until a human grants it again.
 *
 * <p>{@link #type()} and {@link #source()} are columns of their own, beside the document, so that SQL can key on
 * them — the {@code (type, LOWER(source))} unique index is what makes one source identity serve one Organization
 * — but in memory both are read off the configuration, which is the one place they are decided.
 */
public record BriefSource(UUID id, UUID organizationId, BriefSourceConfig config, String lastBuiltCommit,
                          Instant lastPolledInstant, SourceStatus lastStatus, String lastError, Instant insertInstant,
                          Instant updateInstant) {
  /**
   * @return True if the source holds a usable credential.
   */
  public boolean connected() {
    return config.connected();
  }

  /**
   * @return True if the source names what it polls.
   */
  public boolean registered() {
    return config.registered();
  }

  /**
   * @return The identity the {@code source} column carries, or {@code null} until the source is registered.
   */
  public String source() {
    return config.source();
  }

  public BriefSourceType type() {
    return config.type();
  }

  /**
   * @param config        The new configuration.
   * @param updateInstant When it changed.
   * @return This source with that configuration and nothing else changed — the poll history stays.
   */
  public BriefSource withConfig(BriefSourceConfig config, Instant updateInstant) {
    return new BriefSource(id, organizationId, config, lastBuiltCommit, lastPolledInstant, lastStatus, lastError,
        insertInstant, updateInstant);
  }

  /**
   * @param lastBuiltCommit   The commit the Brief was last built from.
   * @param lastPolledInstant When the source was last polled.
   * @param lastStatus        The status the poll produced.
   * @param lastError         Why the cycle failed, or {@code null}.
   * @param updateInstant     When it changed.
   * @return This source with that poll history and nothing else changed.
   */
  public BriefSource withStatus(String lastBuiltCommit, Instant lastPolledInstant, SourceStatus lastStatus,
                                String lastError, Instant updateInstant) {
    return new BriefSource(id, organizationId, config, lastBuiltCommit, lastPolledInstant, lastStatus, lastError,
        insertInstant, updateInstant);
  }
}
