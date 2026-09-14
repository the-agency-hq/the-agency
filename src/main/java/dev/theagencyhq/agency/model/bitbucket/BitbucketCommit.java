/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module org.lattejava.json;

/**
 * The slice of a Bitbucket commit object the Agency reads, from
 * {@code GET /repositories/{workspace}/{repo_slug}/commits/{revision}}: the SHA. Bitbucket returns the author, the
 * message, the parents and a set of links alongside; the poller reads none of them, so none is declared.
 *
 * @param hash The full commit SHA.
 */
@JSON
public record BitbucketCommit(String hash) {
}
