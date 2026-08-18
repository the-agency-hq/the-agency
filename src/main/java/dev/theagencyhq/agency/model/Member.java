/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * A membership: one user's row in one Organization. The {@code user} carries the FusionAuth user UUID always, and
 * the email only after enrichment from FusionAuth — the database stores nothing but the UUID, so a Member read
 * straight off a row has a {@link User} whose display fields are {@code null}.
 *
 * @param organizationId The Organization the membership belongs to.
 * @param user           The member. Only {@code userId} is guaranteed; see above.
 * @param role           The member's role.
 * @param state          PENDING until the invitation is accepted, ACTIVE afterwards.
 * @param invitedBy      The FusionAuth user UUID of the inviter, or {@code null} for the creator's own row.
 * @param invitedAt      When the invitation was sent, or {@code null} for the creator's own row.
 * @param joinedAt       When the member became ACTIVE, or {@code null} while PENDING.
 */
public record Member(
    UUID organizationId,
    User user,
    Role role,
    MembershipState state,
    UUID invitedBy,
    Instant invitedAt,
    Instant joinedAt
) {
  public Member {
    // Truncated to the precision the database stores (BIGINT epoch millis), for the same reason Organization
    // truncates: a Member built in memory and the same one read back must compare equal.
    invitedAt = invitedAt == null ? null : invitedAt.truncatedTo(ChronoUnit.MILLIS);
    joinedAt = joinedAt == null ? null : joinedAt.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * Convenience constructor for callers that only have the user's UUID (the database read path and tests). The
   * email stays {@code null} until the member is enriched from FusionAuth.
   */
  public Member(UUID organizationId, UUID userId, Role role, MembershipState state, UUID invitedBy,
                Instant invitedAt, Instant joinedAt) {
    this(organizationId, new User(userId, null), role, state, invitedBy, invitedAt, joinedAt);
  }

  /**
   * @return The member's FusionAuth user UUID.
   */
  public UUID userId() {
    return user.userId();
  }
}
