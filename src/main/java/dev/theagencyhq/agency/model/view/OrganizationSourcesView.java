/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The Sources page: every kind of Brief source this server offers, with the state of the one the Organization has.
 * This is where a connection is offered and repaired — the OAuth callbacks return here, and the layout shows the
 * outcome they queued — and where a connected source is pointed at its repository. Only ever rendered for an Owner, since everything on it changes
 * what the Organization builds from.
 *
 * @param organization The Organization.
 * @param source       Its source, or {@code null} if it has never had one.
 * @param available    The kinds this server is configured for, in the order the page lists them. Empty when it is
 *                     configured for none, which the page reports instead of offering nothing.
 */
public record OrganizationSourcesView(Organization organization, BriefSource source, List<BriefSourceType> available) {
  /**
   * @param type A kind of source.
   * @return The Organization's source's configuration, if its source is of that kind; otherwise {@code null}, and
   *     the kind's card renders as not connected.
   */
  public BriefSourceConfig config(BriefSourceType type) {
    return source != null && source.type() == type ? source.config() : null;
  }

  /**
   * @return True if the page should warn that the Organization has to be connected to a source, which is exactly
   *     when it holds no credential — no source at all, or a source whose authorization has been removed.
   */
  public boolean needsSource() {
    return source == null || !source.connected();
  }

  /**
   * @param type A kind of source.
   * @return True if connecting that kind would replace a connected source of another kind, which the card says
   *     before the operator starts the trip: an Organization holds one source.
   */
  public boolean replaces(BriefSourceType type) {
    return source != null && source.type() != type && source.connected();
  }

  /**
   * @return True if the Organization's source is of a kind this server is no longer configured for — its card is
   *     not on the page, and the page has to say why the source it has cannot be used.
   */
  public boolean unavailable() {
    return source != null && !available.contains(source.type());
  }
}
