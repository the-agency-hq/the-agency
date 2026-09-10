/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.gitlab;

import module dev.theagencyhq.agency;

/**
 * The {@link RepositoryClient} for GitLab. Adds nothing to the contract: the type exists so the scope holds one
 * client per host under its own name — {@code SourceCatalog} takes one of each — and so a test can hand
 * {@code Main} a fake for exactly this host.
 */
public interface GitLabClient extends RepositoryClient {
}
