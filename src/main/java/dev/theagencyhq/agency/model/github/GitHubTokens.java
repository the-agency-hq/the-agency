/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module java.base;
import module org.lattejava.json;

/**
 * A GitHub user-to-server credential, as the Agency stores it rather than as GitHub returns it: absolute instants
 * instead of the {@code expires_in} durations on the wire.
 *
 * <p>Converting at the edge is what makes the stored value meaningful later. A duration is only interpretable
 * against the moment it was issued, and the moment it was issued is exactly what is missing by the time the poller
 * reads the credential back out of the database hours later.
 *
 * @param accessToken            The user-to-server token, sent as {@code Authorization: Bearer}.
 * @param accessExpiration       When {@code accessToken} stops working, or {@code null} if it never does — which is
 *                               what a GitHub App with expiring user tokens turned off issues.
 * @param refreshToken           The token that buys a new {@code accessToken}, or {@code null} if there is none.
 * @param refreshTokenExpiration When {@code refreshToken} stops working, or {@code null} if there is none.
 */
@JSON
public record GitHubTokens(String accessToken, Instant accessExpiration, String refreshToken,
                           Instant refreshTokenExpiration) {
  /**
   * @param response The body GitHub returned.
   * @param now      The instant the response was received, which the {@code expires_in} durations are relative to.
   * @return The credential, or {@code null} if GitHub reported an error or returned no token.
   */
  public static GitHubTokens from(TokenResponse response, Instant now) {
    if (response == null || response.error() != null || response.accessToken() == null) {
      return null;
    }

    return new GitHubTokens(
        response.accessToken(),
        response.expiresIn() == null ? null : now.plusSeconds(response.expiresIn()),
        response.refreshToken(),
        response.refreshTokenExpiresIn() == null ? null : now.plusSeconds(response.refreshTokenExpiresIn()));
  }

  /**
   * @param now  The current instant.
   * @param skew How far ahead of {@code now} to look. A token that is valid for less than this is treated as
   *             already expired, so it is never handed to a call that would then outlive it.
   * @return True if {@link #accessToken} can still be used.
   */
  public boolean accessTokenValid(Instant now, Duration skew) {
    return accessExpiration == null || now.plus(skew).isBefore(accessExpiration);
  }

  public boolean refreshable(Instant now) {
    return refreshToken != null && (refreshTokenExpiration == null || now.isBefore(refreshTokenExpiration));
  }
}
