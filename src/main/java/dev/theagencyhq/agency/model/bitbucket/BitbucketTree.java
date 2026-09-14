/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module java.base;
import module org.lattejava.json;

/**
 * One page of {@code GET /repositories/{workspace}/{repo_slug}/src/{commit}/?max_depth=...}, the directory listing
 * that stands in for the recursive tree Bitbucket does not have. Paginated like every Bitbucket list: the values
 * and the next page's URL.
 *
 * @param values The entries on the page.
 * @param next   The next page's URL, or {@code null} on the last page.
 */
@JSON
public record BitbucketTree(List<BitbucketTreeEntry> values, String next) {
}
