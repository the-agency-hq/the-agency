/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module java.base;
import module org.lattejava.json;
/**
 * One value of {@code GET /user/workspaces}: the account's membership of one workspace. Bitbucket wraps the
 * workspace in the membership rather than listing workspaces directly, and the Agency reads only the workspace.
 *
 * @param workspace The workspace the membership is of.
 */
@JSON
public record BitbucketWorkspaceAccess(BitbucketWorkspace workspace) {
}
