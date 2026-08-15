/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.Organization;

/**
 * The members page: every member of the Organization, enriched from FusionAuth.
 *
 * @param organization The Organization.
 * @param members      Its members, ACTIVE and PENDING alike.
 * @param viewerUserId The signed-in user's id, so the page never offers Remove or Change role against the viewer's
 *                     own row — those are Leave's job.
 */
public record MembersView(Organization organization, List<Member> members, UUID viewerUserId) {
}
