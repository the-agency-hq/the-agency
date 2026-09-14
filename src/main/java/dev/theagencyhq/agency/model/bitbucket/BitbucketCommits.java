/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module java.base;
import module org.lattejava.json;

/**
 * One page of {@code GET /repositories/{workspace}/{repo_slug}/commits/{revision}}, which lists the commits reachable
 * from a ref newest first. The Agency asks for a page of one, so the first value is the ref's head.
 *
 * @param values The commits on the page.
 */
@JSON
public record BitbucketCommits(List<BitbucketCommit> values) {
}
