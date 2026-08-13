/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.SourceStatus;

/**
 * The Organization listing page: one row per Organization, joined with its Brief source and latest Brief version.
 *
 * @param status The outcome of an OAuth round trip that had no Organization page to return to — a forged or stale
 *               callback, or an Organization deleted mid-authorization — lowercased, or {@code null} if the page
 *               was reached directly. Every outcome that still has an Organization lands on that Organization's
 *               page instead.
 * @param rows   One row per Organization.
 */
public record OrganizationsView(String status, List<Row> rows) {
  /**
   * @param repository The source repository as {@code owner/name}, or the empty string for an Organization that
   *                   has never been connected.
   * @param branch     The branch it builds from, or the empty string.
   */
  public record Row(UUID id, String name, String repository, String branch, SourceStatus status, String error,
                    Integer latestVersion, Instant lastPolledInstant) {
  }
}
