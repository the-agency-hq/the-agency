/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The Agent selection page.
 *
 * @param organization The Organization.
 * @param all          True if the All box is checked — the stored state on first render, the submission's after a
 *                     rejected one.
 * @param selected     The individual Agents checked. Rendered whether or not {@code all} is, so a rejected
 *                     submission comes back exactly as it was sent.
 * @param errors       Why the last submission was rejected, or empty on first render.
 */
public record OrganizationAgentsView(Organization organization, boolean all, List<Agent> selected,
                                     List<String> errors) {
  public boolean isSelected(Agent agent) {
    return selected.contains(agent);
  }
}
