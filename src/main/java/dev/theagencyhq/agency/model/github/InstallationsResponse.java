/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module java.base;
import module org.lattejava.json;

/**
 * The body of {@code GET /user/installations} — every installation of the Agency's GitHub App the signed-in GitHub
 * user can reach.
 *
 * @param totalCount    How many installations there are in total, across every page.
 * @param installations This page of them.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record InstallationsResponse(int totalCount, List<GitHubInstallation> installations) {
}
