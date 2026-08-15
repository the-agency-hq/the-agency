/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.Organization;

/**
 * The leave confirmation page.
 *
 * @param organization The Organization the viewer is leaving.
 * @param membership   The viewer's own membership row, so the page can say what they are leaving as.
 * @param errors       Why leaving was refused — the last-owner rule — or empty on first render.
 */
public record MemberLeaveView(Organization organization, Member membership, List<String> errors) {
}
