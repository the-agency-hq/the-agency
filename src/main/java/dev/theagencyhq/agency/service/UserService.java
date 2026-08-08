/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.jwt;

import dev.theagencyhq.agency.model.User;

/**
 * Translates a validated access token into a {@link User}. Passed to {@code OIDC.api} as the profile's translator,
 * so {@code OIDC#user()} hands out a {@code User} rather than a raw {@code JWT} and no caller has to know which
 * claim carries what.
 *
 * <p>Static, and not registered on {@link Services}, because it holds nothing: every value it needs is in the token
 * the caller presented. The same is true in {@code latte-java/app}, which this mirrors. When the admin UI login
 * arrives it takes the same translator, and when memberships arrive they are looked up by the {@code userId} this
 * produces.
 */
public class UserService {
  /**
   * @param jwt The validated access token.
   * @return The caller it identifies.
   * @throws IllegalStateException If the token carries no {@code sub} claim, which no token that reached here
   *     should — the middleware validated it against the provider's JWKS first — so the failure is a
   *     misconfigured provider rather than a bad request.
   */
  public static User toUser(JWT jwt) {
    var subject = jwt.getString("sub");
    if (subject == null) {
      throw new IllegalStateException("The access token carries no [sub] claim, so the caller cannot be identified");
    }

    return new User(UUID.fromString(subject), jwt.getString("email"), jwt.getString("preferred_username"));
  }
}
