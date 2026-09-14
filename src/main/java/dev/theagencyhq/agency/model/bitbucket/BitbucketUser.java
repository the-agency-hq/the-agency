/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module org.lattejava.json;

/**
 * The slice of {@code GET /user} the Agency reads: a name for the account, stored on the source as display text for
 * the Sources page. Bitbucket still returns the authenticated user's own {@code username}, but has deprecated the
 * member in favour of {@code nickname}, so both are read and either will do.
 *
 * @param username    The Bitbucket username, or {@code null} if Bitbucket stops sending it.
 * @param nickname    The name the account's owner chose.
 * @param displayName The account's display name.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record BitbucketUser(String username, String nickname, String displayName) {
  /**
   * @return The name to show for the account: the username while Bitbucket sends one, then the nickname, then the
   *     display name. Display text — every one of them is mutable on Bitbucket's side, so nothing keys on it.
   */
  public String login() {
    return username != null ? username : nickname != null ? nickname : displayName;
  }
}
