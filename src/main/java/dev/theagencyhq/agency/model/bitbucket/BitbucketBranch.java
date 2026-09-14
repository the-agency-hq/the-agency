/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module org.lattejava.json;

/**
 * The slice of a Bitbucket branch object the Agency reads: the {@code mainbranch} member of a repository, which
 * names the branch a source registers against unless the operator names another.
 *
 * @param name The branch name, with no {@code refs/heads} prefix.
 */
@JSON
public record BitbucketBranch(String name) {
}
