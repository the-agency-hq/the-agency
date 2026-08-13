/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module org.lattejava.json;

/**
 * The slice of {@code GET /user} the Agency reads: the login, stored on the Organization as display text for the
 * connect page. GitHub returns some thirty other members; none of them is used, so none of them is declared.
 *
 * @param login The GitHub username. Display text — it is mutable on GitHub's side, so nothing keys on it.
 */
@JSON
public record GitHubUser(String login) {
}
