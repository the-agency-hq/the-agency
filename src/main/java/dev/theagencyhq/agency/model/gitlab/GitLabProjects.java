/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.gitlab;

import module java.base;
import module org.lattejava.json;

/**
 * One page of {@code GET /projects}. GitLab returns a bare JSON array, which the codec cannot take as a document
 * root, so {@code GitLabHTTPClient} wraps the body as {@code {"items": [...]}} before parsing it into this.
 *
 * @param items The projects on the page.
 */
@JSON
public record GitLabProjects(List<GitLabProject> items) {
}
