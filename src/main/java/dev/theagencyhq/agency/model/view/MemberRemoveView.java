/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.Organization;

/**
 * The remove-member confirmation page.
 *
 * @param organization The Organization.
 * @param member       The member being removed, enriched so the page can name them.
 * @param errors       Why the removal was refused, or empty on first render.
 */
public record MemberRemoveView(Organization organization, Member member, List<String> errors) {
}
