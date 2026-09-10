/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.Main;
import dev.theagencyhq.agency.tests.source.FakeRepositoryClient;

import static org.testng.Assert.*;

/**
 * The OAuth handshake with a repository host and the credential it produces, for whichever host the subclass names:
 * the routes in {@code RepositorySourceController}, and the {@code brief_sources} document {@code SourceLinkService}
 * reads and writes. Every test here runs once per kind of source, which is the proof that the code above the
 * client cannot tell the hosts apart; what one host has and another does not — GitHub's install trip — lives in
 * that host's own subclass.
 *
 * <p>Postgres is real here — the credentials these tests store are genuinely written into the
 * {@code brief_sources.source_config} document and read back out of it — because that storage is the whole point
 * of the design and stubbing it would leave the part most worth proving untested. Only the host itself is faked.
 */
public abstract class RepositoryConnectionTestBase extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();

  /**
   * The operator can be away at the host for minutes, and the Organization can be deleted in that window. The
   * exchange still runs — the code was genuine — but there is no row left to hang a source off, which is exactly
   * what {@code LINK_FAILED} reports. It reports it on the listing: the one destination the callback normally
   * uses, the Organization's Sources page, is a 404 by definition here.
   */
  @Test
  public void aCallbackForADeletedOrganizationStoresNothing() {
    var organizationId = createOrganization(slug() + "-deleted-" + UUID.randomUUID());
    var state = startConnection(organizationId);
    organizationService.delete(organizationId);

    test.get(callbackPath() + "?code=the-code&state=" + state)
        .assertRedirect(303, "/app/organizations/");

    assertFalse(organizations.findById(organizationId).isPresent());
    assertTrue(sources.findAll().isEmpty());
    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("link_failed", label())));
  }

  /**
   * A state cookie written for a trip to one host must not complete a callback from another: the cookie carries the
   * kind, and a return on the other kind's callback is a mismatch even with the right nonce. Nothing is stored for
   * either.
   */
  @Test
  public void aCallbackForAnotherKindLinksNothing() {
    var organizationId = createOrganization(slug() + "-other-kind-" + UUID.randomUUID());
    var state = startConnection(organizationId);
    var other = type() == BriefSourceType.GITHUB ? BriefSourceType.GITLAB : BriefSourceType.GITHUB;

    test.get(RepositorySourceController.callbackPath(other) + "?code=the-code&state=" + state)
        .assertRedirect(303, "/app/organizations/");

    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
  }

  /**
   * A callback whose state does not match the cookie is not merely ignored — it must not perform the exchange, or
   * the forgery it exists to prevent has already happened by the time the mismatch is noticed.
   */
  @Test
  public void aCallbackWithTheWrongStateLinksNothing() {
    var organizationId = createOrganization(slug() + "-forged-" + UUID.randomUUID());
    startConnection(organizationId);

    test.get(callbackPath() + "?code=any&state=not-the-nonce")
        .assertRedirect(303, "/app/organizations/");

    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("state_mismatch", label())));
  }

  /**
   * A callback with no state cookie at all — a bookmarked callback URL, or a cross-site request that never went
   * through {@code /start}. Same outcome, and it must not depend on the cookie merely disagreeing.
   */
  @Test
  public void aCallbackWithoutAStateCookieLinksNothing() {
    var organizationId = createOrganization(slug() + "-no-cookie-" + UUID.randomUUID());

    test.get(callbackPath() + "?code=any&state=anything")
        .assertRedirect(303, "/app/organizations/");

    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
  }

  /**
   * The credential is stored inside the Organization's own source row and nowhere else, and storing it is what
   * creates that row: a source that is connected and not yet pointed at a repository. Reading it back through a
   * second service call — rather than through whatever {@code link} happened to return — and then straight out of
   * the JSON document itself is what proves it round-tripped rather than being cached.
   */
  @Test
  public void aConnectedOrganizationStoresTheCredentialOnItsSourceRow() {
    var organizationId = createOrganization(slug() + "-stored-" + UUID.randomUUID());
    var accessToken = link(type(), organizationId);

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.type(), type());
    assertTrue(source.connected());
    assertEquals(connection(organizationId).login(), fake().login());
    assertEquals(links.accessToken(organizationId), accessToken);

    var row = database.dsl()
                .resultQuery("SELECT type, source, source_config->>'type', source_config->'connection'->>'login', "
                    + "source_config->'connection'->'tokens'->>'accessToken' FROM brief_sources WHERE organization_id = ?",
                    organizationId)
                .fetchOne();
    assertEquals(row.get(0, String.class), type().name());
    assertNull(row.get(1, String.class));
    assertEquals(row.get(2, String.class), type().name());
    assertEquals(row.get(3, String.class), fake().login());
    assertEquals(row.get(4, String.class), accessToken);

    // Still no repository: authorizing the host and registering a repository are two separate steps, and the first
    // must not fabricate the second.
    assertFalse(source.registered());
    assertNull(source.source());
  }

  /**
   * GitHub returns to the callback with {@code error} instead of {@code code} when the operator declines, and GitLab
   * does the same. That is not a failure of the Agency's, so it lands back on the Sources page rather than an error
   * page — naming the host, since there is no source row yet to read the kind off — and, crucially, with no
   * credential stored.
   */
  @Test
  public void aDeclinedAuthorizationReturnsToTheSourcesPage() {
    var organizationId = createOrganization(slug() + "-declined-" + UUID.randomUUID());
    var state = startConnection(organizationId);

    test.get(callbackPath() + "?error=access_denied&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources");

    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("exchange_failed", label())));
  }

  /**
   * A revocation on the host's side, discovered by the picker: the stored token is still inside its lifetime, so
   * the 401 on the listing call is the first thing to learn the truth. The picker removes the dead credential and
   * returns to the Sources page, which now warns and offers the reconnect — rather than bouncing back silently
   * forever while the row still claims a working connection.
   */
  @Test
  public void aRevokedCredentialIsRemovedByThePicker() {
    fake().add("acme", "briefs");
    var organizationId = createOrganization(slug() + "-revoked-" + UUID.randomUUID());
    link(type(), organizationId);
    fake().revokeAll();

    test.get(pickerPath(organizationId))
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources");

    assertNull(connection(organizationId));

    // The page the bounce lands on is no longer a dead end: it offers the reconnect, not the picker.
    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("not connected to a Brief source")
                                    .contains(startPath() + "?organizationId=" + organizationId)
                                    .doesNotContain("Change repository"));
  }

  /**
   * A refresh token the host has stopped honouring is the end of the authorization: there is nothing left to try,
   * so the dead credential is removed on the spot. The admin UI reads a stored credential as a working connection,
   * so a dead one left behind would keep the Sources page offering a repository picker that can never load instead
   * of the reconnect that fixes it. The source row itself stays — it is the Organization's source, merely
   * disconnected.
   */
  @Test
  public void anUnrefreshableCredentialIsRemoved() {
    var organizationId = createOrganization(slug() + "-dead-" + UUID.randomUUID());
    fake().tokenLifetime(Duration.ofSeconds(-1));
    assertEquals(links.link(type(), organizationId, "code", "http://localhost/callback"),
        SourceLinkService.LinkResult.LINKED);
    fake().failRefresh(true);

    assertNull(links.accessToken(organizationId));

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.type(), type());
    assertFalse(source.connected());
    assertNull(connection(organizationId));
  }

  @Test
  public void anExchangeTheHostRejectsLinksNothing() {
    var organizationId = createOrganization(slug() + "-badcode-" + UUID.randomUUID());
    fake().failExchange(true);

    assertEquals(links.link(type(), organizationId, "bad-code", "http://localhost/callback"),
        SourceLinkService.LinkResult.EXCHANGE_FAILED);

    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
  }

  /**
   * Re-authorizing an Organization that already builds from a repository must not lose the repository: the
   * connection is one member of the source document and the merge writes only that member.
   */
  @Test
  public void connectingAgainKeepsTheRegisteredRepository() {
    fake().add("acme", "briefs");
    var organizationId = createOrganization(slug() + "-relink-repo-" + UUID.randomUUID());
    link(type(), organizationId);
    connect(organizationId, "acme/briefs");

    link(type(), organizationId);

    var config = sources.findByOrganizationId(organizationId).orElseThrow().config();
    assertEquals(config.fullName(), "acme/briefs");
    assertEquals(config.branch(), "main");
    assertEquals(sources.findByOrganizationId(organizationId).orElseThrow().source(), "acme/briefs");
  }

  /**
   * Linking twice replaces the credential rather than accumulating them: the connection member is overwritten in
   * place, so the poller always reads exactly the credential the latest authorization produced.
   */
  @Test
  public void connectingAgainReplacesTheStoredCredential() {
    var organizationId = createOrganization(slug() + "-relink-" + UUID.randomUUID());
    var first = link(type(), organizationId);
    var sourceId = sources.findByOrganizationId(organizationId).orElseThrow().id();
    var second = link(type(), organizationId);

    assertNotEquals(second, first);
    assertEquals(links.accessToken(organizationId), second);
    // The same row, not a second one.
    assertEquals(sources.findByOrganizationId(organizationId).orElseThrow().id(), sourceId);
    assertEquals(sources.findAll().size(), 1);
  }

  /**
   * An Organization holds one source. Connecting the other host replaces the source — the row, its kind, its
   * repository and its history — rather than sitting beside it, and the Sources page says so before the trip
   * starts.
   */
  @Test
  public void connectingAnotherKindReplacesTheSource() {
    var other = type() == BriefSourceType.GITHUB ? BriefSourceType.GITLAB : BriefSourceType.GITHUB;
    fake(other).add("acme", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createOrganization(slug() + "-replace-kind-" + UUID.randomUUID());
    link(other, organizationId);
    connect(organizationId, "acme/briefs");
    assertEquals(runCycle(organizationId), SourceStatus.OK);

    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Connecting " + label() + " replaces the Organization's " + other.label() + " source"));

    link(type(), organizationId);

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.type(), type());
    assertTrue(source.connected());
    assertFalse(source.registered());
    assertNull(source.lastStatus());
    assertEquals(sources.findAll().size(), 1);
    // The Brief that was published stays published: a change of source says nothing about the content it produced.
    assertEquals(briefs.findLatestByOrganizationId(organizationId).orElseThrow().version().intValue(), 1);
  }

  /**
   * The reconnect that follows a revocation is judged by the row, not by the last poll. The cycle that discovered
   * the revocation left NOT_CONNECTED on the source, and none has run since the new credential was stored — the
   * suite's poller thread is switched off, so nothing can have — which is exactly the window in which the page
   * used to warn that the Organization was not connected while reporting, in the same breath, that the host was.
   * The picker link and the picker itself have to be back in that window too.
   */
  @Test
  public void reconnectingAfterARevocationClearsTheWarningBeforeTheNextPoll() {
    fake().add("acme", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createOrganization(slug() + "-reconnect-" + UUID.randomUUID());
    link(type(), organizationId);
    connect(organizationId, "acme/briefs");
    assertEquals(runCycle(organizationId), SourceStatus.OK);
    fake().revokeAll();
    assertEquals(runCycle(organizationId), SourceStatus.NOT_CONNECTED);

    var state = startConnection(organizationId);
    test.get(callbackPath() + "?code=the-code&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources");

    assertEquals(sources.findByOrganizationId(organizationId).orElseThrow().lastStatus(), SourceStatus.NOT_CONNECTED);
    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("linked", label()))
                                    .doesNotContain("not connected to a Brief source")
                                    .contains("acme/briefs")
                                    .contains("Change repository"));
    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.doesNotContain("not connected to a Brief source"));

    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("acme/briefs").contains("Use this repository"));
  }

  // Every route here is behind the gate, so the session is established once rather than at the top of each method.
  @BeforeMethod
  public void signIn() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
  }

  @Test
  public void startIs404ForAnUnknownOrganization() {
    test.get(startPath() + "?organizationId=" + UUID.randomUUID())
        .assertStatus(404);
    test.get(startPath() + "?organizationId=not-a-uuid")
        .assertStatus(404);
    test.get(startPath())
        .assertStatus(404);
  }

  /**
   * Starting a connection sends the browser to the host with the application's client id and the Agency's own
   * callback, and leaves the state behind in an encrypted, path-scoped, {@code Lax} cookie. {@code Lax} rather than
   * {@code Strict} because the callback arrives as a top-level navigation from the host — {@code Strict} would
   * withhold the cookie on precisely the request it exists for.
   */
  @Test
  public void startSendsTheBrowserToTheHostAndRemembersTheState() {
    var organizationId = createOrganization(slug() + "-start-" + UUID.randomUUID());

    var state = new AtomicReference<String>();
    var cookie = new AtomicReference<String>();
    test.get(startPath() + "?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          assertTrue(location.startsWith(authorizeURL()), location);
          assertTrue(location.contains("client_id=" + main.config.get(slug() + ".clientId")), location);
          assertTrue(location.contains("redirect_uri=http%3A%2F%2Flocalhost%3A" + TEST_PORT
              + "%2Fapp%2Foauth%2F" + slug() + "%2Fcallback"), location);
          state.set(location.substring(location.indexOf("&state=") + "&state=".length()));

          cookie.set(r.headers()
                      .allValues("Set-Cookie")
                      .stream()
                      .filter(c -> c.startsWith(RepositorySourceController.STATE_COOKIE + "="))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No state cookie: " + r.headers().allValues("Set-Cookie"))));
        });

    assertFalse(state.get().isEmpty());
    assertTrue(cookie.get().contains("Path=" + RepositorySourceController.COOKIE_PATH), cookie.get());
    assertTrue(cookie.get().contains("SameSite=Lax"), cookie.get());
    // Encrypted, so neither the nonce nor the Organization it belongs to is legible in the cookie -- and the state
    // in the URL is the only half an attacker can see, which is why it is a nonce and carries nothing.
    assertFalse(cookie.get().contains(state.get()), cookie.get());
    assertFalse(cookie.get().contains(organizationId.toString()), cookie.get());
  }

  /**
   * The whole round trip through HTTP, and then the first build: start, come back with the matching state, end up
   * connected, pick a repository, and poll it. Everything except the host itself is real — the routes, the cookie,
   * the stored credential, the build.
   */
  @Test
  public void theFullRoundTripConnectsTheAccountAndBuilds() {
    fake().add("acme", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createOrganization(slug() + "-roundtrip-" + UUID.randomUUID());
    var state = startConnection(organizationId);

    test.get(callbackPath() + "?code=the-code&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources");

    assertNotNull(connection(organizationId));

    // The Sources page now reads as connected and offers the picker, and the picker is reachable and populated,
    // where before the handshake it redirected away -- the externally visible difference the whole thing exists
    // to produce.
    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("linked", label()))
                                    .contains("Connected as")
                                    .contains(fake().login())
                                    .contains("Connect a repository")
                                    .contains(pickerPath(organizationId)));
    // Shown once: a reload of the page reports its state, not the trip that is over.
    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.doesNotContain(messages().get("linked", label())).contains("Connect a repository"));
    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("acme/briefs").contains("Use this repository"));

    test.withFormField("repository", "acme/briefs")
        .withFormField("branch", "main")
        .post(pickerPath(organizationId))
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    assertEquals(runCycle(organizationId), SourceStatus.OK);
    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.type(), type());
    assertEquals(source.source(), "acme/briefs");
    assertEquals(briefs.findLatestByOrganizationId(organizationId).orElseThrow().version().intValue(), 1);

    // The Organization's page and the Sources page both link the repository on its host, as the source describes
    // itself: the kind, then the rows its configuration supplies.
    var url = source.config().url();
    assertNotNull(url);
    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(">Source<").contains(label()).contains(url).contains("acme/briefs")
                                    .contains(">Branch<").contains(">main<"));
    test.get("/app/organizations/" + organizationId + "/sources")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(url).contains("Change repository"));
  }

  /**
   * @return Where {@code /start} sends the browser: the prefix of the host's authorization URL.
   */
  protected abstract String authorizeURL();

  /**
   * @return The kind of source this class connects.
   */
  protected abstract BriefSourceType type();

  protected String callbackPath() {
    return RepositorySourceController.callbackPath(type());
  }

  protected FakeRepositoryClient fake() {
    return fake(type());
  }

  /**
   * The outcome messages exactly as the server queues them: the bundle every {@code /app/oauth} route shares, read
   * from the same files and for the same path.
   */
  protected Messages messages() {
    return new Messages(Main.BASE_DIR, callbackPath());
  }

  protected String label() {
    return type().label();
  }

  protected String pickerPath(UUID organizationId) {
    return "/app/organizations/" + organizationId + "/sources/" + slug();
  }

  protected String slug() {
    return type().slug();
  }

  /**
   * Runs {@code /start} and returns the state it put in the authorize URL, leaving the matching cookie in the
   * shared jar so a callback can be made against it.
   */
  protected String startConnection(UUID organizationId) {
    var state = new AtomicReference<String>();
    test.get(startPath() + "?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          state.set(location.substring(location.indexOf("&state=") + "&state=".length()));
        });
    return state.get();
  }

  protected String startPath() {
    return "/app/oauth/" + slug() + "/start";
  }
}
