/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.gitlab;

import module org.lattejava.json;

/**
 * The slice of a GitLab commit object the Agency reads, from
 * {@code GET /projects/{id}/repository/commits/{ref}}: the SHA the ref resolves to. GitLab returns the author, the
 * message, the parents and the stats alongside; the poller reads none of them, so none is declared.
 *
 * @param id The full commit SHA.
 */
@JSON
public record GitLabCommit(String id) {
}
