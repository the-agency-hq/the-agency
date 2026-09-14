/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module org.lattejava.json;

/**
 * The slice of a Bitbucket repository object the Agency reads, from {@code GET /repositories/{workspace}}. Bitbucket
 * returns dozens of members and a set of links per repository; these two are the ones the picker needs, and the
 * client asks Bitbucket for only them.
 *
 * @param fullName   {@code workspace/repository}, which is how Bitbucket names a repository in its own URLs, and so
 *                   what the picker shows and what the form posts back.
 * @param mainbranch The branch a source registers against unless the operator names another. {@code null} for an
 *                   empty repository.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record BitbucketRepository(String fullName, BitbucketBranch mainbranch) {
}
