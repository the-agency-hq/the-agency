/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module java.base;
import module org.lattejava.json;
/**
 * One page of {@code GET /user/workspaces}, which lists the workspaces the authenticated account belongs to. Paged
 * as every Bitbucket list is: the values, and the URL of the next page in the body — absent on the last page.
 *
 * @param values The account's workspace memberships on the page.
 * @param next   The next page's URL, or {@code null} on the last page.
 */
@JSON
public record BitbucketWorkspaces(List<BitbucketWorkspaceAccess> values, String next) {
}
