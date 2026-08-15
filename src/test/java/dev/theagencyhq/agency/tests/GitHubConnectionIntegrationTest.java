/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.controller.GitHubController;
import dev.theagencyhq.agency.service.GitHubLinkService;
import dev.theagencyhq.agency.tests.github.FakeGitHubClient;

import static org.testng.Assert.*;

/**
 * The GitHub OAuth handshake and the credential it produces: the two routes in {@code GitHubController}, and the
 * {@code organizations} columns {@code GitHubLinkService} reads and writes.
 *
 * <p>Postgres is real here — the credentials these tests store are genuinely written to the {@code organizations}
 * table and read back out of it — because that storage is the whole point of the design and stubbing it would
 * leave the part most worth proving untested. Only GitHub itself is faked.
 */
@Test(groups = "integration")
public class GitHubConnectionIntegrationTest extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();

  /**
   * The operator can be away at GitHub for minutes, and the Organization can be deleted in that window. The
   * exchange still runs — the code was genuine — but there is no row left to store the credential on, which is
   * exactly what {@code LINK_FAILED} reports. It reports it on the listing: the one destination the callback
   * normally uses, the Organization's own page, is a 404 by definition here.
   */
  @Test
  public void aCallbackForADeletedOrganizationStoresNothing() {
    var organizationId = createOrganization("github-deleted-" + UUID.randomUUID());
    var state = startConnection(organizationId);
    organizationService.delete(organizationId);

    test.get(GitHubController.CALLBACK_PATH + "?code=the-code&state=" + state)
        .assertRedirect(303, "/app/organizations/?status=link_failed");

    var organization = db.findOrganization(organizationId);
    assertFalse(organization.isPresent());
  }

  /**
   * A callback whose state does not match the cookie is not merely ignored — it must not perform the exchange, or
   * the forgery it exists to prevent has already happened by the time the mismatch is noticed.
   */
  @Test
  public void aCallbackWithTheWrongStateLinksNothing() {
    var organizationId = createOrganization("github-forged-" + UUID.randomUUID());
    startConnection(organizationId);

    test.get(GitHubController.CALLBACK_PATH + "?code=any&state=not-the-nonce")
        .assertRedirect(303, "/app/organizations/?status=state_mismatch");

    var organization = db.findOrganization(organizationId);
    assertNull(organization.orElseThrow().gitHubConnection());
  }

  /**
   * A callback with no state cookie at all — a bookmarked callback URL, or a cross-site request that never went
   * through {@code /start}. Same outcome, and it must not depend on the cookie merely disagreeing.
   */
  @Test
  public void aCallbackWithoutAStateCookieLinksNothing() {
    var organizationId = createOrganization("github-no-cookie-" + UUID.randomUUID());

    test.get(GitHubController.CALLBACK_PATH + "?code=any&state=anything")
        .assertRedirect(303, "/app/organizations/?status=state_mismatch");

    var organization = db.findOrganization(organizationId);
    assertNull(organization.orElseThrow().gitHubConnection());
  }

  /**
   * GitHub returns to the callback with {@code error} instead of {@code code} when the operator declines. That is
   * not a failure of the Agency's, so it lands back on the Organization's page rather than an error page — and,
   * crucially, with no credential stored.
   */
  @Test
  public void aDeclinedAuthorizationReturnsToTheOrganizationPage() {
    var organizationId = createOrganization("github-declined-" + UUID.randomUUID());
    var state = startConnection(organizationId);

    test.get(GitHubController.CALLBACK_PATH + "?error=access_denied&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "?status=exchange_failed");

    var organization = db.findOrganization(organizationId);
    assertNull(organization.orElseThrow().gitHubConnection());
  }

  /**
   * The credential is stored in columns on the Organization's own row and nowhere else. Reading it back through a
   * second service call — rather than through whatever {@code link} happened to return — and then straight off the
   * row itself is what proves it round-tripped rather than being cached.
   */
  @Test
  public void aConnectedOrganizationStoresTheCredentialOnItsRow() {
    var organizationId = createOrganization("github-stored-" + UUID.randomUUID());
    var accessToken = linkGitHub(organizationId);

    var organization = db.findOrganization(organizationId);
    assertNotNull(organization.orElseThrow().gitHubConnection());
    assertEquals(organization.orElseThrow().gitHubConnection().login(), FakeGitHubClient.USER_LOGIN);
    assertEquals(links.accessToken(organizationId), accessToken);

    var row = db.dsl()
                .resultQuery("SELECT github_access_token, github_login FROM organizations WHERE id = ?", organizationId)
                .fetchOne();
    assertEquals(row.get(0, String.class), accessToken);
    assertEquals(row.get(1, String.class), FakeGitHubClient.USER_LOGIN);

    // Still no source: authorizing GitHub and registering a repository are two separate steps, and the first must
    // not fabricate the second.
    assertEquals(db.dsl().resultQuery("SELECT count(*) FROM brief_sources").fetchOne(0, int.class).intValue(), 0);
  }

  /**
   * Linking twice replaces the credential rather than accumulating them: the columns are overwritten in place, so
   * the poller always reads exactly the credential the latest authorization produced.
   */
  @Test
  public void connectingAgainReplacesTheStoredCredential() {
    var organizationId = createOrganization("github-relink-" + UUID.randomUUID());
    var first = linkGitHub(organizationId);
    var second = linkGitHub(organizationId);

    assertNotEquals(second, first);
    assertEquals(links.accessToken(organizationId), second);
  }

  /**
   * A refresh token GitHub has stopped honouring is the end of the authorization: there is nothing left to try, and
   * saying so is what lets the poller report NOT_CONNECTED instead of retrying a dead credential forever.
   */
  @Test
  public void anUnrefreshableCredentialReportsNoAccessToken() {
    var organizationId = createOrganization("github-dead-" + UUID.randomUUID());
    github.tokenLifetime(Duration.ofSeconds(-1));
    assertEquals(links.link(organizationId, "code", "http://localhost/callback"),
        GitHubLinkService.LinkResult.LINKED);
    github.failRefresh(true);

    assertNull(links.accessToken(organizationId));

    // The credential itself survives, deliberately: it is the Agency's record that this Organization was once
    // connected, and the operator's fix is to reconnect, not to have the record silently vanish.
    var organization = db.findOrganization(organizationId);
    assertNotNull(organization.orElseThrow().gitHubConnection());
  }

  @Test
  public void anExchangeGitHubRejectsLinksNothing() {
    var organizationId = createOrganization("github-badcode-" + UUID.randomUUID());
    github.failExchange(true);

    assertEquals(links.link(organizationId, "bad-code", "http://localhost/callback"),
        GitHubLinkService.LinkResult.EXCHANGE_FAILED);

    var organization = db.findOrganization(organizationId);
    assertNull(organization.orElseThrow().gitHubConnection());
  }

  // Every route here is behind the gate, so the session is established once rather than at the top of each method.
  @BeforeMethod
  public void signIn() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
  }

  /**
   * Starting a connection sends the browser to GitHub with the App's client id and the Agency's own callback, and
   * leaves the state behind in an encrypted, path-scoped, {@code Lax} cookie. {@code Lax} rather than {@code Strict}
   * because the callback arrives as a top-level navigation from github.com — {@code Strict} would withhold the
   * cookie on precisely the request it exists for.
   */
  @Test
  public void startSendsTheBrowserToGitHubAndRemembersTheState() {
    var organizationId = createOrganization("github-start-" + UUID.randomUUID());

    var state = new AtomicReference<String>();
    var cookie = new AtomicReference<String>();
    test.get("/app/oauth/github/start?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          assertTrue(location.startsWith("https://github.com/login/oauth/authorize"), location);
          assertTrue(location.contains("client_id=" + main.config.get("github.clientId")), location);
          assertTrue(location.contains("redirect_uri=http%3A%2F%2Flocalhost%3A" + TEST_PORT
              + "%2Fapp%2Foauth%2Fgithub%2Fcallback"), location);
          state.set(location.substring(location.indexOf("&state=") + "&state=".length()));

          cookie.set(r.headers()
                      .allValues("Set-Cookie")
                      .stream()
                      .filter(c -> c.startsWith(GitHubController.STATE_COOKIE + "="))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No state cookie: " + r.headers().allValues("Set-Cookie"))));
        });

    assertFalse(state.get().isEmpty());
    assertTrue(cookie.get().contains("Path=" + GitHubController.COOKIE_PATH), cookie.get());
    assertTrue(cookie.get().contains("SameSite=Lax"), cookie.get());
    // Encrypted, so neither the nonce nor the Organization it belongs to is legible in the cookie -- and the state
    // in the URL is the only half an attacker can see, which is why it is a nonce and carries nothing.
    assertFalse(cookie.get().contains(state.get()), cookie.get());
    assertFalse(cookie.get().contains(organizationId.toString()), cookie.get());
  }

  @Test
  public void startIs404ForAnUnknownOrganization() {
    test.get("/app/oauth/github/start?organizationId=" + UUID.randomUUID())
        .assertStatus(404);
    test.get("/app/oauth/github/start?organizationId=not-a-uuid")
        .assertStatus(404);
    test.get("/app/oauth/github/start")
        .assertStatus(404);
  }

  /**
   * The whole round trip through HTTP: start, come back with the matching state, and end up connected. Everything
   * except github.com itself is real — the routes, the cookie, the stored credential.
   */
  @Test
  public void theFullRoundTripConnectsTheAccount() {
    github.add("acme", "briefs");
    var organizationId = createOrganization("github-roundtrip-" + UUID.randomUUID());
    var state = startConnection(organizationId);

    test.get(GitHubController.CALLBACK_PATH + "?code=the-code&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "?status=linked");

    var organization = db.findOrganization(organizationId);
    assertNotNull(organization.orElseThrow().gitHubConnection());

    // And the repository picker is now reachable and populated, where before the handshake it redirected away --
    // the externally visible difference the whole thing exists to produce.
    test.get("/app/organizations/" + organizationId + "/connect")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("acme/briefs").contains("Use this repository"));
  }

  /**
   * Runs {@code /start} and returns the state it put in the authorize URL, leaving the matching cookie in the
   * shared jar so a callback can be made against it.
   */
  private String startConnection(UUID organizationId) {
    var state = new AtomicReference<String>();
    test.get("/app/oauth/github/start?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          state.set(location.substring(location.indexOf("&state=") + "&state=".length()));
        });
    return state.get();
  }
}
