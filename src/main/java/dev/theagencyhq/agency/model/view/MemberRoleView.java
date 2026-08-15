/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.Role;

/**
 * The change-role page for one member.
 *
 * @param organization The Organization.
 * @param member       The member whose role is changing, enriched so the page can name them.
 * @param selectedRole The role the form shows selected — the member's current role on first render, the rejected
 *                     submission's choice after one.
 * @param errors       Why the last submission was rejected, or empty on first render.
 */
public record MemberRoleView(Organization organization, Member member, Role selectedRole, List<String> errors) {
}
