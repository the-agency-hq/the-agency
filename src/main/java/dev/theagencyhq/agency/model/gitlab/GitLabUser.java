/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.gitlab;

import module org.lattejava.json;

/**
 * The slice of {@code GET /user} the Agency reads: the username, stored on the source as display text for the
 * Sources page. GitLab returns some forty other members; none of them is used, so none of them is declared.
 *
 * @param username The GitLab username. Display text — it is mutable on GitLab's side, so nothing keys on it.
 */
@JSON
public record GitLabUser(String username) {
}
