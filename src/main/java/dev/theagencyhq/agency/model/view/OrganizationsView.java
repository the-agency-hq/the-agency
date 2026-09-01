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
 * @param status      The outcome of an OAuth round trip that had no Organization page to return to — a forged or
 *                    stale callback, or an Organization deleted mid-authorization — lowercased, or {@code null} if
 *                    the page was reached directly. Every outcome that still has an Organization lands on that
 *                    Organization's page instead.
 * @param invitations The viewer's PENDING invitations, rendered above the listing with Accept and Decline.
 * @param rows        One row per Organization the viewer is an ACTIVE member of.
 */
public record OrganizationsView(String status, List<Invitation> invitations, List<Row> rows) {
  /**
   * @param id   The Organization the viewer is invited to.
   * @param name Its display name.
   * @param role The role accepting would grant.
   */
  public record Invitation(UUID id, String name, Role role) {
  }

  /**
   * @param repository The source repository as {@code owner/name}, or the empty string for an Organization that
   *                   has never been connected.
   * @param branch     The branch it builds from, or the empty string.
   */
  public record Row(UUID id, String name, String repository, String branch, SourceStatus status, String error,
                    Integer latestVersion, Instant lastPolledInstant) {
  }
}
