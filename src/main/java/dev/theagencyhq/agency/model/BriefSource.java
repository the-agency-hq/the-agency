/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The local Git working tree that a Brief is built from. Exactly one per Organization.
 *
 * <p>{@code lastError} and {@code lastPullError} are separate because a cycle can legitimately have both a failed
 * pull and a successful build — the common case being a local test repository with no remote configured at all.
 */
public record BriefSource(UUID id, UUID organizationId, String path, String lastBuiltCommit,
                          Instant lastPolledInstant, SourceStatus lastStatus, String lastError, String lastPullError,
                          Instant insertInstant, Instant updateInstant) {
  public BriefSource {
    path = path == null ? null : path.trim();
  }
}
