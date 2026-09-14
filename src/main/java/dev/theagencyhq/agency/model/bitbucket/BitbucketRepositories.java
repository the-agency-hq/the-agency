/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module java.base;
import module org.lattejava.json;

/**
 * One page of {@code GET /repositories/{workspace}}. Bitbucket pages every list the same way: the values, and the
 * URL of the next page in the body — absent on the last page — which the client follows rather than building its
 * own, as Bitbucket asks.
 *
 * @param values The repositories on the page.
 * @param next   The next page's URL, or {@code null} on the last page.
 */
@JSON
public record BitbucketRepositories(List<BitbucketRepository> values, String next) {
}
