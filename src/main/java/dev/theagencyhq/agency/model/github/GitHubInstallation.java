/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module org.lattejava.json;

/**
 * One installation of the Agency's GitHub App, as {@code GET /user/installations} reports it. An installation is the
 * grant itself: the App installed on an account, over the repositories that account chose to give it.
 *
 * <p>Only the id, because that is all the Agency does with an installation — list the repositories under it. Which
 * account it belongs to is already on every repository's {@code full_name}, and the picker groups by nothing.
 *
 * @param id The installation id, which the repository listing is keyed by.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record GitHubInstallation(long id) {
}
