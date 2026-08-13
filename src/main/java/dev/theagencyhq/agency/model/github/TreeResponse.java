/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module java.base;
import module org.lattejava.json;

/**
 * The body of {@code GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1}.
 *
 * <p>{@code truncated} is the member that matters as much as the entries do. GitHub caps a recursive tree at
 * roughly 100,000 entries or 7 MB of response and then silently returns a prefix of it, so a build that ignored the
 * flag would publish a Brief whose files had quietly reverted to the default mode. The caller fails the build
 * instead.
 *
 * @param sha       The tree's own SHA.
 * @param tree      The entries, flattened by {@code recursive=1}.
 * @param truncated Whether GitHub dropped entries from {@code tree}.
 */
@JSON
public record TreeResponse(String sha, List<TreeEntry> tree, boolean truncated) {
}
