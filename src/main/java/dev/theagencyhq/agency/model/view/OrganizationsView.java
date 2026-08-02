/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.SourceStatus;

/**
 * The Organization listing page: one row per Organization, joined with its Brief source and latest Brief version.
 */
public record OrganizationsView(List<Row> rows) {
  public record Row(UUID id, String name, String path, SourceStatus status, String error, String pullError,
                    Integer latestVersion, Instant lastPolledInstant) {
  }
}
