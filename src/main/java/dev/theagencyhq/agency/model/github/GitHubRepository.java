/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module org.lattejava.json;

/**
 * The slice of a GitHub repository object the Agency reads, from
 * {@code GET /user/installations/{id}/repositories}. GitHub returns around a hundred members per repository; these
 * two are the ones the connect page needs, and declaring only them keeps the codec from having an opinion about
 * the other ninety-eight.
 *
 * @param fullName      {@code owner/name}, which is how GitHub names a repository everywhere an operator has seen
 *                      one, and so what the picker shows and what the form posts back.
 * @param defaultBranch The branch a source registers against unless the operator names another.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record GitHubRepository(String fullName, String defaultBranch) {
}
