/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

import dev.theagencyhq.agency.model.Member;

/**
 * An Organization's view page: the Organization itself (which carries its GitHub connection), its single Brief
 * source, its version history in full (newest first), the viewer's own membership, and the outcome of an OAuth
 * round trip that just ended, if one did — because this page is where the connection is offered, reported, and
 * repaired.
 *
 * @param organization The Organization.
 * @param source       Its current source, or {@code null} if none is registered.
 * @param versions     Every version of its Brief, newest first.
 * @param membership   The viewer's membership row. Never {@code null}: the route sits behind
 *                     {@code OrganizationSecurity}, which admits nobody without one.
 * @param status       The outcome of the OAuth round trip that just returned here, lowercased, or {@code null} if
 *                     the page was reached directly.
 */
public record OrganizationDetailView(Organization organization, BriefSource source, List<Brief> versions,
                                     Member membership, String status) {
  /**
   * @return True if the viewer is an ACTIVE member of any role — the state in which working actions (rebuild,
   *     leave) are offered.
   */
  public boolean activeMember() {
    return membership.state() == MembershipState.ACTIVE;
  }

  /**
   * @return True if the viewer is an ACTIVE OWNER — the state in which the management actions (members, the GitHub
   *     connection, the repository) are offered.
   */
  public boolean canManage() {
    return activeMember() && membership.role() == Role.OWNER;
  }

  /**
   * @return True if the viewer's membership is a pending invitation, which is when the page leads with Accept and
   *     Decline instead of the working actions.
   */
  public boolean invited() {
    return membership.state() == MembershipState.PENDING;
  }

  /**
   * @return True if the page should warn that the Organization has to be (re)connected to GitHub. No credential at
   *     all, or a credential the last poll proved dead: two ways to be disconnected, one warning, because the
   *     operator's fix is the same button either way. A dead credential no poll has tripped over yet is
   *     deliberately not chased down here — the next cycle is at most a minute away and will record it.
   */
  public boolean needsGitHubConnection() {
    return organization.gitHubConnection() == null
        || (source != null && source.lastStatus() == SourceStatus.NOT_CONNECTED);
  }
}
