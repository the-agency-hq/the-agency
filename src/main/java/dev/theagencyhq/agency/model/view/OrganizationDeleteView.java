/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The delete confirmation page: what goes with the Organization, and the name the viewer has to type to confirm.
 *
 * @param organization The Organization being deleted.
 * @param source       Its source, or {@code null} if none is connected.
 * @param versions     How many Brief versions go with it.
 * @param members      How many membership rows go with it, the viewer's own included.
 * @param confirmation What the viewer typed, re-rendered after a refusal; empty on first render.
 * @param errors       Why the deletion was refused — the name did not match — or empty on first render.
 */
public record OrganizationDeleteView(Organization organization, BriefSource source, int versions, int members,
                                     String confirmation, List<String> errors) {
}
