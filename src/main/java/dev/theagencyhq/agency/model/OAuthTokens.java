/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;
import module org.lattejava.json;

/**
 * An OAuth credential for a repository host, as the Agency stores it rather than as the host returns it: absolute
 * instants instead of the {@code expires_in} durations on the wire.
 *
 * <p>Converting at the edge is what makes the stored value meaningful later. A duration is only interpretable
 * against the moment it was issued, and the moment it was issued is exactly what is missing by the time the poller
 * reads the credential back out of the database hours later.
 *
 * <p>The instants serialize as epoch millis, the precision and the form every instant column in the schema uses.
 * The member names are the contract of the stored {@code brief_sources.source_config} document, which every
 * connected source already carries, so they do not change with the Java name.
 *
 * @param accessToken            The bearer token, sent as {@code Authorization: Bearer}.
 * @param accessExpiration       When {@code accessToken} stops working, or {@code null} if it never does — which is
 *                               what a GitHub App with expiring user tokens turned off issues.
 * @param refreshToken           The token that buys a new {@code accessToken}, or {@code null} if there is none.
 * @param refreshTokenExpiration When {@code refreshToken} stops working, or {@code null} if it never does — GitLab
 *                               issues refresh tokens without an expiry.
 */
@JSON
public record OAuthTokens(String accessToken,
                          @JSONField(instant = InstantFormat.EPOCH_MILLIS) Instant accessExpiration,
                          String refreshToken,
                          @JSONField(instant = InstantFormat.EPOCH_MILLIS) Instant refreshTokenExpiration) {
  /**
   * @param response The body the host returned.
   * @param now      The instant the response was received, which the {@code expires_in} durations are relative to.
   * @return The credential, or {@code null} if the host reported an error or returned no token.
   */
  public static OAuthTokens from(OAuthTokenResponse response, Instant now) {
    if (response == null || response.error() != null || response.accessToken() == null) {
      return null;
    }

    return new OAuthTokens(
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
