/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The Organization listing page: the viewer's pending invitations first, then one row per Organization they are a
 * member of, joined with its Brief source and latest Brief version. An Organization appears in exactly one of the
 * two — an invitation is not yet a membership, and the invitation banner (with its Accept and Decline) is how it
 * becomes one.
 *
 * @param invitations The viewer's PENDING invitations, rendered above the listing with Accept and Decline.
 * @param rows        One row per Organization the viewer is an ACTIVE member of.
 */
public record OrganizationsView(List<Invitation> invitations, List<Row> rows) {
  /**
   * @param id   The Organization the viewer is invited to.
   * @param name Its display name.
   * @param role The role accepting would grant.
   */
  public record Invitation(UUID id, String name, Role role) {
  }

  /**
   * @param role       The viewer's role in the Organization, which decides whether an empty source cell offers the
   *                   Sources page — only an Owner can reach it.
   * @param sourceType The kind of source the Organization builds from, or {@code null} if it has not registered
   *                   one.
   * @param source     The source's identity — the repository as its host names it — or {@code null} if the
   *                   Organization has not registered one.
   * @param branch     The branch the source builds from, or {@code null}.
   */
  public record Row(UUID id, String name, Role role, BriefSourceType sourceType, String source, String branch,
                    SourceStatus status, String error, Integer latestVersion, Instant lastPolledInstant) {
    public boolean canManage() {
      return role == Role.OWNER;
    }
  }
}
