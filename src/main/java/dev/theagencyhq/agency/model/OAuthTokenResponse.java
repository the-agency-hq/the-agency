/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module org.lattejava.json;

/**
 * The JSON body an OAuth token endpoint returns, for both the authorization-code exchange and the refresh-token
 * grant. GitHub ({@code POST https://github.com/login/oauth/access_token} with {@code Accept: application/json})
 * and GitLab ({@code POST /oauth/token}) answer with the same member names, so one record reads both; the members
 * a host does not send are {@code null}.
 *
 * <p>GitHub answers HTTP 200 for both outcomes: a successful exchange carries {@code access_token}, and a failed one
 * carries {@code error} and no token. GitLab answers a failure with a 4xx and the same {@code error} members. So a
 * client may not infer success from the status code alone, and {@link OAuthTokens#from} judges the body.
 *
 * <p>{@code expiresIn} and {@code refreshToken} are only populated when the host issues expiring tokens: a GitHub
 * App with expiring user tokens turned off returns a token that never expires and no refresh token at all, so both
 * are {@code null} — which {@link OAuthTokens} carries through as "no expiration to track".
 *
 * @param accessToken           The access token, or {@code null} when the exchange failed.
 * @param error                 The error code (for example {@code bad_verification_code}), or {@code null} on success.
 * @param errorDescription      The host's prose for {@code error}. Surfaced in log messages only.
 * @param expiresIn             Seconds until {@code accessToken} expires, or {@code null} if it does not.
 * @param refreshToken          The refresh token, or {@code null} if the host does not issue them.
 * @param refreshTokenExpiresIn Seconds until {@code refreshToken} expires, or {@code null} if it never does or there
 *                              is none. GitHub sends it; GitLab's refresh tokens carry no expiry.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record OAuthTokenResponse(String accessToken, String error, String errorDescription, Long expiresIn,
                                 String refreshToken, Long refreshTokenExpiresIn) {
}
