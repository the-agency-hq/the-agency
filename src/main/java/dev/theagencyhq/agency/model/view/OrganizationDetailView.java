/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Brief;
import dev.theagencyhq.agency.model.Organization;

/**
 * An Organization's detail page: the Organization itself, its single Brief source, and its version history
 * in full, newest first.
 */
public record OrganizationDetailView(Organization organization, BriefSource source, List<Brief> versions) {
}
