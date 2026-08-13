/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module org.lattejava.json;

/**
 * The JSON body GitHub returns from {@code POST https://github.com/login/oauth/access_token}, for both the
 * authorization-code exchange and the refresh-token grant, when the request carries {@code Accept: application/json}.
 *
 * <p>GitHub answers HTTP 200 for both outcomes: a successful exchange carries {@code access_token}, and a failed one
 * carries {@code error} and no token. Nothing may infer failure from the status code.
 *
 * <p>{@code expiresIn} and {@code refreshToken} are only populated when the GitHub App has expiring user tokens
 * turned on. With them off, GitHub returns a token that never expires and no refresh token at all, so both are
 * {@code null} — which {@link GitHubTokens} carries through as "no expiration to track".
 *
 * @param accessToken           The user-to-server access token, or {@code null} when the exchange failed.
 * @param error                 The error code (for example {@code bad_verification_code}), or {@code null} on success.
 * @param errorDescription      GitHub's prose for {@code error}. Surfaced in log messages only.
 * @param expiresIn             Seconds until {@code accessToken} expires, or {@code null} if it does not.
 * @param refreshToken          The refresh token, or {@code null} if the App does not issue them.
 * @param refreshTokenExpiresIn Seconds until {@code refreshToken} expires, or {@code null} if there is none.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record TokenResponse(String accessToken, String error, String errorDescription, Long expiresIn,
                            String refreshToken, Long refreshTokenExpiresIn) {
}
