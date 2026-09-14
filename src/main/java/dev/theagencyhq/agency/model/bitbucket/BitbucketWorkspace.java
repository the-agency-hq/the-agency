/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module java.base;
import module org.lattejava.json;
/**
 * The slice of a Bitbucket workspace object the Agency reads: its slug, which is the first segment of every one of
 * its repositories' full names and the path segment {@code GET /repositories/{workspace}} takes.
 *
 * @param slug The workspace's slug.
 */
@JSON
public record BitbucketWorkspace(String slug) {
}
