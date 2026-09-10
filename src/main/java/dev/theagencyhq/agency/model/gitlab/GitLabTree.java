/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.gitlab;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * One page of {@code GET /projects/{id}/repository/tree?recursive=true}. GitLab returns a bare JSON array, which the
 * codec cannot take as a document root, so {@code GitLabHTTPClient} wraps the body as {@code {"items": [...]}}
 * before parsing it into this. Unlike GitHub's tree, GitLab's is paginated rather than truncated: a large tree is
 * many pages, and the client walks them.
 *
 * @param items The entries on the page.
 */
@JSON
public record GitLabTree(List<TreeEntry> items) {
}
