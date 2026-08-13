/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module java.base;
import module org.lattejava.json;

/**
 * The body of {@code GET /user/installations/{installation_id}/repositories} — the repositories one installation of
 * the Agency's GitHub App can see, filtered to the ones this particular user can see.
 *
 * @param totalCount   How many repositories the installation covers in total, across every page.
 * @param repositories This page of them.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record RepositoriesResponse(int totalCount, List<GitHubRepository> repositories) {
}
