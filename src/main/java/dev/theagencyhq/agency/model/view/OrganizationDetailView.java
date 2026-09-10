/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

import dev.theagencyhq.agency.model.Member;

/**
 * An Organization's view page: the Organization itself, its single Brief source, its version history in full
 * (newest first), and the viewer's own membership. The page warns when the source is missing or disconnected and
 * points an Owner at the Sources page, which is where the connection is offered and repaired. What a registered
 * source consists of is the source's own to say: the page renders the rows its configuration describes itself
 * with ({@code BriefSourceConfig#details()}).
 *
 * @param organization The Organization.
 * @param source       Its current source, or {@code null} if none is registered.
 * @param versions     Every version of its Brief, newest first.
 * @param membership   The viewer's membership row. Never {@code null}: the route sits behind
 *                     {@code OrganizationSecurity}, which admits nobody without one.
 */
public record OrganizationDetailView(Organization organization, BriefSource source, List<Brief> versions,
                                     Member membership) {
  /**
   * @return True if the viewer is an ACTIVE member of any role — the state in which working actions (rebuild,
   *     leave) are offered.
   */
  public boolean activeMember() {
    return membership.state() == MembershipState.ACTIVE;
  }

  /**
   * @return True if the viewer is an ACTIVE OWNER — the state in which the management actions (members, Agents,
   *     sources) are offered.
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
   * @return True if the page should warn that the Organization has to be connected to a source, which is exactly
   *     when it holds no credential: no source at all, or one whose authorization has been removed. Every path that
   *     proves a credential dead — a refresh the host rejects, a poll or a picker it refuses — removes it from the
   *     source on the spot, so a missing credential is the one disconnected state there is. The source's last poll
   *     status is deliberately not consulted: it reads NOT_CONNECTED until the cycle after a reconnect, so a stored
   *     credential beside that status is what an Organization reconnected a moment ago looks like, not a
   *     disconnected one.
   */
  public boolean needsSource() {
    return source == null || !source.connected();
  }

  /**
   * @return True if the source has been told what to poll, which is when the page has a source to describe and a
   *     "Rebuild now" to offer.
   */
  public boolean registered() {
    return source != null && source.registered();
  }
}
