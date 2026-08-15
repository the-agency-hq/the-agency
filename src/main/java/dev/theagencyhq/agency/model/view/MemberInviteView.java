/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.Role;

/**
 * The invite form, carrying what was submitted so a rejected submission re-renders with the values still in place.
 *
 * @param organization The Organization the invitation is for.
 * @param email        The submitted email, or the empty string on first render.
 * @param role         The selected role.
 * @param errors       Why the last submission was rejected, or empty on first render.
 */
public record MemberInviteView(Organization organization, String email, Role role, List<String> errors) {
}
