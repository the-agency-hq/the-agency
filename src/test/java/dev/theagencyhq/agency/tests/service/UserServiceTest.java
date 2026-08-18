/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.lattejava.jwt;
import module org.testng;

import dev.theagencyhq.agency.model.User;
import dev.theagencyhq.agency.service.UserService;

import static org.testng.Assert.*;

/**
 * The claim-to-{@link User} mapping, driven with hand-built tokens. A pure function over a JWT, so it needs neither
 * a server nor a provider — the tokens the real thing sees are covered by every API test in the suite, which cannot
 * return a {@code 200} unless this translator ran.
 *
 * <p>The claims are asserted by name because those names are a contract with FusionAuth's JWT-populate lambda in
 * {@code src/main/fusionauth/kickstart}: {@code email} is in an access token only because that lambda puts it
 * there. Renaming it on either side has to fail here.
 */
@Test
public class UserServiceTest {
  private static final UUID USER_ID = UUID.fromString("c00890fd-7c92-42fc-9f20-d9f429ba293b");

  @Test
  public void everyClaimIsMapped() {
    var user = UserService.toUser(JWT.builder()
                                     .subject(USER_ID.toString())
                                     .claim("email", "admin@theagencyhq.dev")
                                     .build());

    assertEquals(user, new User(USER_ID, "admin@theagencyhq.dev"));
  }

  /**
   * A provider that omits the optional claims still identifies its caller. The Agency keys on {@code userId} and
   * nothing else, so a token carrying only {@code sub} is enough to authenticate — dropping the request instead
   * would make the display text load-bearing.
   */
  @Test
  public void missingOptionalClaimsAreNull() {
    var user = UserService.toUser(JWT.builder().subject(USER_ID.toString()).build());

    assertEquals(user, new User(USER_ID, null));
  }

  /**
   * A token with no {@code sub} cannot name anyone. It should not be reachable — the middleware validated the token
   * against the provider's JWKS before the translator ran — so this fails loudly rather than yielding a User with a
   * null id that every later caller would have to defend against.
   */
  @Test
  public void missingSubjectIsRejected() {
    var jwt = JWT.builder().claim("email", "admin@theagencyhq.dev").build();

    assertThrows(IllegalStateException.class, () -> UserService.toUser(jwt));
  }

  /**
   * FusionAuth's {@code sub} is a UUID, and {@link User#userId()} is typed as one. A provider that issued
   * something else would otherwise be discovered as a {@code ClassCastException} somewhere downstream.
   */
  @Test
  public void nonUUIDSubjectIsRejected() {
    var jwt = JWT.builder().subject("not-a-uuid").build();

    assertThrows(IllegalArgumentException.class, () -> UserService.toUser(jwt));
  }
}
