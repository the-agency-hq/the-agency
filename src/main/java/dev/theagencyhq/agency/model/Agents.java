/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;
import module org.lattejava.json;

/**
 * An Organization's Agent selection: the document in {@code organizations.agents} and inside a Brief's
 * Organization, verbatim — one type for both, so a field added here widens the column and the wire together.
 * {@code enabled} is the Agents the Organization narrowed to. The type exists only when the selection is narrowed:
 * every Agent is a {@code null} {@link Organization#agents()}, never an instance of this.
 */
@JSON
public record Agents(List<Agent> enabled) {
  public Agents {
    // Canonical because it feeds the Brief checksum: sorted (the enum's natural order) and deduplicated, so the
    // same selection always serializes the same way however the form or the database handed it over.
    enabled = enabled == null ? null : enabled.stream().filter(Objects::nonNull).distinct().sorted().toList();
  }

  /**
   * @param agents A selection, or {@code null} for every Agent.
   * @return The selection as the admin UI shows it: {@code All}, or the labels comma-separated in order.
   */
  public static String describe(Agents agents) {
    return agents == null || agents.enabled() == null
        ? "All"
        : agents.enabled().stream().map(Agent::label).collect(Collectors.joining(", "));
  }

  /**
   * @param agent An Agent.
   * @return True if the selection includes the Agent.
   */
  public boolean serves(Agent agent) {
    return enabled == null || enabled.contains(agent);
  }
}
