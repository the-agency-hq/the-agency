/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * One row of what the admin UI shows about a registered source, as the source's own configuration describes it:
 * the Organization's page renders the list a {@link BriefSourceConfig} returns and knows nothing about what a kind
 * of source consists of.
 *
 * @param label The row's name — "Repository", "Branch".
 * @param value The row's text.
 * @param url   Where the value lives on its host, for the row to link to, or {@code null} for plain text.
 */
public record SourceDetail(String label, String value, String url) {
}
