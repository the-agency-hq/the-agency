/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The GitHub repository a Brief is built from. Exactly one per Organization.
 *
 * <p>The row carries no credential of its own: the poller works with the GitHub authorization stored on the owning
 * Organization's row, which the operator granted when they connected it. When that authorization lapses the source
 * goes {@link SourceStatus#NOT_CONNECTED} and stays there until someone reconnects it, which is the honest
 * outcome: nobody is entitled to that repository until a human grants it again.
 *
 * <p>{@code owner} and {@code repository} are stored as GitHub spells them, because that is what the admin UI shows
 * and what a URL is built from. Uniqueness across Organizations is case-insensitive, enforced by the {@code LOWER()}
 * unique index rather than by flattening the stored value, exactly as Organization names are.
 */
public record BriefSource(UUID id, UUID organizationId, String owner, String repository, String branch,
                          String lastBuiltCommit, Instant lastPolledInstant, SourceStatus lastStatus,
                          String lastError, Instant insertInstant, Instant updateInstant) {
  public BriefSource {
    owner = owner == null ? null : owner.trim();
    repository = repository == null ? null : repository.trim();
    branch = branch == null ? null : branch.trim();
  }

  /**
   * @return {@code owner/repository}, the form GitHub itself uses everywhere and the one the admin UI renders.
   */
  public String fullName() {
    return owner + "/" + repository;
  }

  /**
   * @return The repository's URL on github.com, for the admin UI to link to.
   */
  public String url() {
    return "https://github.com/" + owner + "/" + repository;
  }
}
