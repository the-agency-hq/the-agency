/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.assertTrue;

/**
 * The browser half of authentication: the gate on {@code /app}, the session endpoints behind it, and the boundary
 * between the two OAuth clients. {@link AdminUITest} covers what the pages do once someone is signed in; this covers
 * getting signed in, and being turned away.
 *
 * <p>Every test here either calls {@code ssrOIDC.login(...)} — which drives a real authorization-code flow and
 * leaves the session in the shared cookie jar — or deliberately does not. {@code BaseTest} logs out after each
 * method, so "does not" means anonymous.
 */
@Test
public class AdminUIAuthenticationTest extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();

  /**
   * A Handler session is not an admin session. {@code apiOIDC.login} parks the Handler Application's tokens in the
   * cookie jar under the very names the browser profile reads, which is exactly the confusion worth testing: they
   * are real tokens, from the same provider, for the same user, and they still do not open the admin UI. The access
   * token fails the audience check and the refresh token cannot rescue it, because refreshing it presents the
   * Agency's client credentials for a token issued to a different client. Two Applications is what makes this a
   * rejection rather than a login.
   */
  @Test
  public void aHandlerSessionDoesNotOpenTheAdminUI() throws Exception {
    apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);

    test.get("/app/organizations/")
        .assertRedirect(302, LOGIN_PATH);
  }

  /**
   * The mirror of {@link #aHandlerSessionDoesNotOpenTheAdminUI}: an admin's browser token is not a Handler
   * credential either. Same user, same provider, wrong audience.
   */
  @Test
  public void anAdminSessionDoesNotCallTheBriefingAPI() throws Exception {
    var tokens = ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);

    test.withHeader("Authorization", "Bearer " + tokens.accessToken())
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(401);
  }

  /**
   * The gate itself. Not an error page and not a {@code 401} — for a browser the right answer is to go get a
   * session, and the challenge records where the visitor was headed so the callback can put them back there.
   */
  @Test
  public void anAnonymousVisitorIsSentToLogin() {
    test.get("/app/organizations/")
        .assertRedirect(302, LOGIN_PATH)
        .assertResponse(r -> assertTrue(
            r.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("oidc_return_to=")),
            "The challenge did not record where the visitor was headed: " + r.headers().allValues("Set-Cookie")));
  }

  /**
   * Every page under the prefix, not just the listing the other tests use. The gate is installed on {@code /app}
   * rather than per route precisely so a page added later cannot miss it, and that only means something if it is
   * asserted against more than one path.
   */
  @Test
  public void everyAdminPathIsGated() {
    for (var path : List.of("/app/organizations/", "/app/organizations/new",
        "/app/organizations/" + UUID.randomUUID(),
        "/app/organizations/" + UUID.randomUUID() + "/connect",
        "/app/organizations/" + UUID.randomUUID() + "/versions/1",
        // The GitHub handshake is inside the gate for a reason of its own: the callback identifies the connecting
        // operator from the session, so an ungated callback would let anyone reaching the URL attach a GitHub
        // account to somebody else's identity.
        "/app/oauth/github/start?organizationId=" + UUID.randomUUID(),
        "/app/oauth/github/callback?code=x&state=y")) {
      test.get(path)
          .assertRedirect(302, LOGIN_PATH);
    }
  }

  /**
   * {@code /login} is one of the four endpoints the session middleware installs. It hands the browser to FusionAuth
   * with this Agency's client id — not the Handler's — and with PKCE, which is what the callback later proves.
   */
  @Test
  public void loginRedirectsToTheProvider() {
    var clientId = main.config.get("fusionauth.clientId");

    test.get(LOGIN_PATH)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          assertTrue(location.startsWith(main.config.get("fusionauth.issuer") + "/oauth2/authorize"), location);
          assertTrue(location.contains("client_id=" + clientId), location);
          assertTrue(location.contains("code_challenge_method=S256"), location);
          assertTrue(location.contains("redirect_uri=http%3A%2F%2Flocalhost%3A" + TEST_PORT + "%2Foidc%2Freturn"),
              location);
        });
  }

  /**
   * The end of the logout round trip. FusionAuth sends the browser back here after ending its own session, and this
   * is where the Agency's cookies are dropped and the visitor is sent to the front door.
   */
  @Test
  public void logoutReturnClearsTheSessionCookies() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);

    test.get("/oidc/logout-return")
        .assertRedirect(302, "/")
        .assertResponse(r -> {
          var cleared = r.headers().allValues("Set-Cookie");
          for (var name : List.of("access_token", "id_token", "refresh_token")) {
            assertTrue(cleared.stream().anyMatch(c -> c.startsWith(name + "=") && c.contains("Max-Age=0")),
                "The [" + name + "] cookie was not cleared: " + cleared);
          }
        });
  }

  /**
   * A POST, because the sign-out control in the layout is a form. The handler answers a meta-refresh rather than a
   * redirect on POST — a form post through a strict CSP cannot always be redirected — so the provider's logout URL
   * is in the body rather than in a {@code Location} header.
   */
  @Test
  public void logoutSendsTheBrowserToTheProvider() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);

    test.post("/logout")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(main.config.get("fusionauth.issuer") + "/oauth2/logout")
                                    .contains("post_logout_redirect_uri"));
  }

  /**
   * The signed-in chrome, which is the only place the translated {@link dev.theagencyhq.agency.model.User} is
   * visible from outside the server. Asserting the username here is what ties the layout, the {@code viewer}
   * template parameter, and {@code UserService}'s claim mapping together end to end.
   */
  @Test
  public void theSignedInChromeNamesTheUserAndOffersSignOut() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);

    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("AdminUser")
                                    .contains(TEST_EMAIL)
                                    .contains("action=\"/logout\""));
  }
}
