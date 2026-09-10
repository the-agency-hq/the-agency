/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.fusionauth;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.Main;
import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.User;
import dev.theagencyhq.agency.tests.source.*;

import static org.testng.Assert.*;

/**
 * The base for every test that needs the application: the server, the database, or the service singletons. Pure unit
 * tests — path mapping, JSON shapes, SemVer, the Mission Type resolver — do not extend this and should not, since
 * booting a server to test a regex is pure cost.
 *
 * <p>One {@link Main} for the whole suite, started in {@code @BeforeSuite}. TestNG runs classes sequentially, and
 * only one of them can bind the port, so per-class startup would be both wasteful and impossible.
 *
 * <p>{@link #TEST_PORT} is deliberately not {@link Main#PORT}. A development server left running on 8080 used to
 * make every HTTP test class fail in configuration with "one of the listeners threw an exception", which reads like a
 * broken build rather than an occupied port.
 *
 * <p>The repository hosts are the one dependency the suite fakes. {@link #github} and {@link #gitlab} are in-memory
 * hosts, handed to {@code Main} so every service is built on them; everything else — FusionAuth, Postgres — is the
 * real thing running locally, and the credentials these tests store are genuinely written to and read back from
 * the {@code brief_sources} table.
 *
 * <p>{@code @BeforeMethod} truncates the database and empties the fakes, so every test starts from empty and no test
 * has to bookkeep what it created. {@code organizations} is the only root: {@code brief_sources} and {@code briefs}
 * both cascade from it — and every credential lives in a source row — so deleting it clears everything.
 */
public abstract class BaseTest {
  /**
   * A second FusionAuth user, provisioned by {@code src/main/fusionauth/kickstart}, for everything membership: it
   * is the user that gets invited, removed, and turned away, while {@link #TEST_EMAIL} owns whatever the test
   * created.
   */
  public static final String ORDINARY_EMAIL = "user@theagencyhq.dev";
  /**
   * The FusionAuth user every test authenticates as, provisioned by {@code src/main/fusionauth/kickstart}.
   */
  public static final String TEST_EMAIL = "admin@theagencyhq.dev";
  /**
   * A fixed instant for every seeded row. Both instant columns are {@code BIGINT} epoch millis and Organization
   * truncates to that precision, so a fixed millisecond value round-trips through the database unchanged — which
   * lets a test compare a seeded record with one read back, field for field.
   */
  public static final Instant TEST_INSTANT = Instant.ofEpochMilli(1_700_000_000_000L);
  public static final String TEST_PASSWORD = "password";
  public static final int TEST_PORT = 8081;
  /**
   * A loopback redirect the Handler Application accepts. Its registered redirect carries a wildcard port because the
   * real Handler binds an ephemeral one; nothing listens on this port during a suite run, since the fixture reads
   * the authorization code off the redirect rather than following it.
   */
  public static final String TEST_REDIRECT_URI = "http://127.0.0.1:8888/callback";
  /**
   * Where the browser profile's challenge sends an unauthenticated visitor. The default {@code BrowserSettings}
   * login path, and the {@code Location} an admin UI request without cookies has to carry.
   */
  public static final String LOGIN_PATH = "/login";
  /**
   * The Briefing API's login fixture, bound to the Handler Application. Distinct from {@link #ssrOIDC} because the
   * two profiles are two OAuth clients: a token from one carries the wrong {@code aud} for the other, which is the
   * whole point of keeping the Applications separate.
   */
  public static OIDCTestFixture apiOIDC;
  public static BriefingService briefingService;
  public static BriefRepository briefs;
  public static SourceCatalog catalog;
  public static Database database;
  public static BriefSourceRepository sources;
  /**
   * The real FusionAuth client, for the membership tests to look up and clean up the users the invite flow
   * creates. Everything else authenticates through the OIDC fixtures rather than this.
   */
  public static FusionAuthClient fusionAuth;
  public static FakeRepositoryClient github = new FakeRepositoryClient("agency-test");
  public static FakeRepositoryClient gitlab = new FakeRepositoryClient("agency-test-gitlab");
  public static SourceLinkService links;
  public static Main main;
  public static MemberRepository members;
  public static MembershipService membershipService;
  /**
   * The {@link #ORDINARY_EMAIL} user as the application sees it, resolved from FusionAuth in {@code beforeSuite}
   * because the kickstart generates the UUID.
   */
  public static User ordinaryUser;
  public static OrganizationRepository organizations;
  public static OrganizationService organizationService;
  public static PollerService pollerService;
  /**
   * The {@link #TEST_EMAIL} user as the application sees it — the creator and OWNER of everything the helpers
   * below insert. Resolved from FusionAuth in {@code beforeSuite} because the kickstart generates the UUID.
   */
  public static User testUser;
  /**
   * The admin UI's login fixture, bound to the Agency Application.
   */
  public static OIDCTestFixture ssrOIDC;
  public static WebTest test = new WebTest(TEST_PORT);

  @AfterSuite
  public static void afterSuite() {
    if (main != null) {
      main.close();
    }
  }

  @BeforeSuite
  public static void beforeSuite() throws Exception {
    main = new Main(TEST_PORT, true, github, gitlab);
    main.main();
    briefingService = main.inject(BriefingService.class);
    briefs = main.inject(BriefRepository.class);
    catalog = main.inject(SourceCatalog.class);
    database = main.inject(Database.class);
    sources = main.inject(BriefSourceRepository.class);
    links = main.inject(SourceLinkService.class);
    members = main.inject(MemberRepository.class);
    membershipService = main.inject(MembershipService.class);
    organizations = main.inject(OrganizationRepository.class);
    organizationService = main.inject(OrganizationService.class);
    pollerService = main.inject(PollerService.class);
    apiOIDC = new OIDCTestFixture(test, main.apiConfig);
    ssrOIDC = new OIDCTestFixture(test, main.ssrConfig, main.ssrSettings);

    // The two kickstart users, resolved once for the whole suite: the UUIDs are generated at kickstart time, and
    // membership rows key on them. The lookups double as the provisioning check they always were -- a missing user
    // turns into one clear message here instead of a login failure in every HTTP test class.
    fusionAuth = new FusionAuthClient(main.config.get("fusionauth.apiKey"), main.config.get("fusionauth.baseURL"));
    testUser = kickstartUser(TEST_EMAIL);
    ordinaryUser = kickstartUser(ORDINARY_EMAIL);
  }

  /**
   * @param email One of the kickstart-provisioned users' emails.
   * @return The user as the application sees it.
   */
  public static User kickstartUser(String email) {
    var response = fusionAuth.retrieveUser(null, null, null, null, email, null);
    assertNotNull(response, "FusionAuth has no user [" + email + "]. Run `docker compose up -d` in "
        + "src/main/fusionauth, and `docker compose down -v` first if it was provisioned from an older "
        + "kickstart.json");
    return UserService.toUser(response.user());
  }

  /**
   * A Brief file with a real checksum over its own content, so a Brief built from these is internally consistent
   * the way one out of the builder is.
   *
   * @param path    The Brief-relative output path.
   * @param content The file's text.
   * @return The file.
   */
  public static BriefFile briefFile(String path, String content) {
    return new BriefFile(path, BriefFile.DEFAULT_ENCODING, BriefFile.DEFAULT_MODE, content,
        Checksums.sha256Hex(content.getBytes(StandardCharsets.UTF_8)), List.of("web"));
  }

  /**
   * Builds the response the Briefing API must produce, in the canonical order §10.2 specifies: both arrays sorted
   * by the Organization id's String form, so {@code organizationIds[i]} and {@code briefs[i]} describe the same
   * Organization. Restating that rule here is the point — comparing a whole expected response against the whole
   * actual one asserts the ordering, the entitled set, and every member of every Brief in a single equality.
   *
   * @param entitled Every Organization the Handler may receive Briefs for.
   * @param briefs   The Briefs that should be delivered, in any order.
   * @return The expected response.
   */
  public static BriefingResponse briefingResponse(List<Organization> entitled, List<Brief> briefs) {
    return new BriefingResponse(
        entitled.stream().map(o -> o.id().toString()).sorted().toList(),
        briefs.stream().sorted(Comparator.comparing(b -> b.organization().id().toString())).toList());
  }

  /**
   * Inserts a Brief for an Organization, straight to the database and without going near the builder or a
   * repository. A test that cares what the Briefing API does with a stored Brief does not also want to care how one
   * is produced.
   *
   * @param organization The owning Organization.
   * @param checksum     The content checksum, which most callers just need to be distinguishable.
   * @param files        The Brief's files, if the test cares what they are.
   * @return The stored Brief, carrying the version the insert assigned.
   */
  public static Brief insertBrief(Organization organization, String checksum, BriefFile... files) {
    return briefs.create(new Brief(checksum, organization, null, List.of(files), "abc", TEST_INSTANT));
  }

  /**
   * Inserts a membership row directly, for tests that need a specific role or state without walking the invite
   * flow.
   *
   * @param organization The Organization.
   * @param user         The member.
   * @param role         Their role.
   * @param state        ACTIVE or PENDING.
   * @return The inserted member.
   */
  public static Member insertMember(Organization organization, User user, Role role, MembershipState state) {
    var member = new Member(organization.id(), user.userId(), role, state,
        state == MembershipState.PENDING ? testUser.userId() : null,
        state == MembershipState.PENDING ? TEST_INSTANT : null,
        state == MembershipState.ACTIVE ? TEST_INSTANT : null);
    members.create(member);
    return member;
  }

  /**
   * Inserts an Organization under a generated name. Names are unique case-insensitively, and the database is shared
   * by the whole suite, so a generated name is the only kind that cannot collide with another test's.
   *
   * @return The inserted Organization.
   */
  public static Organization insertOrganization() {
    return insertOrganization("org-" + UUID.randomUUID());
  }

  /**
   * Inserts an Organization with {@link #testUser} seated as its ACTIVE OWNER, matching what creating one through
   * the service or the form produces — and what nearly every test needs now that memberships gate both the admin
   * UI and the APIs. A test that wants an Organization the test user cannot see inserts a
   * {@code new Organization(...)} through {@link OrganizationRepository#create} directly.
   *
   * @param name The Organization's display name.
   * @return The inserted Organization.
   */
  public static Organization insertOrganization(String name) {
    var organization = new Organization(UUID.randomUUID(), name, null, TEST_INSTANT, TEST_INSTANT);
    organizations.create(organization);
    insertMember(organization, testUser, Role.OWNER, MembershipState.ACTIVE);
    return organization;
  }

  /**
   * Empties every table. Public and static so the one class that opts out of the automatic reset can still call it
   * where it wants one.
   */
  public static void resetDatabase() {
    // ON DELETE CASCADE carries brief_sources and briefs with it, so this is the whole graph. Raw SQL because no
    // repository exposes a bulk delete on its production API.
    database.dsl().execute("DELETE FROM organizations");
  }

  /**
   * Ends whatever session the method established, as {@code latte-java/app}'s own base class does. A test signs in
   * once at the top and every request it makes after that is signed in; this is what keeps the session from
   * outliving the method and quietly authenticating the next one.
   *
   * <p>Load-bearing, not decoration: without it {@code anAnonymousVisitorIsSentToLogin} and
   * {@code everyAdminPathIsGated} both pass on a session some earlier method left behind.
   *
   * <p>Named for what it does rather than {@code afterMethod} because {@link AdminUIIntegrationTest} declares one of those: a
   * subclass method with the same signature would override this one, and the override would silently skip it.
   */
  @AfterMethod(alwaysRun = true)
  public void logoutAfterMethod() {
    ssrOIDC.logout();
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    resetDatabase();
    github.reset();
    gitlab.reset();

    // The tester is shared by the whole suite and accumulates headers, form fields, and a body until something
    // clears them. Clearing here means a method starts from nothing, exactly as it starts with an empty database,
    // so no chain has to end in a reset() purely to protect whatever runs next. Twelve tests fail without it.
    test.clearRequestState();
  }

  /**
   * Registers a repository on an Organization's source through the service, as the picker form does. The
   * Organization has to hold a credential first — {@link #link(BriefSourceType, UUID)} — because that is what
   * creates the source row, decides which host the repository is verified against, and provides the token it is
   * verified with.
   *
   * @param organizationId The Organization to connect.
   * @param fullName       The repository as its host names it.
   */
  protected void connect(UUID organizationId, String fullName) {
    connect(organizationId, fullName, "main");
  }

  protected void connect(UUID organizationId, String fullName, String branch) {
    var type = sources.findByOrganizationId(organizationId).orElseThrow().type();
    organizationService.connect(type, organizationId, links.accessToken(organizationId), fullName, branch);
  }

  /**
   * Posts the name form the way the admin UI does and returns the Organization it created, reading the id off the
   * redirect to its page.
   *
   * @param name The Organization's display name.
   * @return Its id.
   */
  protected UUID createOrganization(String name) {
    var location = new AtomicReference<String>();
    test.withFormField("name", name)
        .post("/app/organizations/")
        .assertStatus(303)
        .assertResponse(r -> location.set(r.headers().firstValue("Location").orElseThrow()))
        // Request only, never Cookies: the caller signed in once and the rest of its requests still need that
        // session. What has to go is this method's own form fields, which would otherwise ride along on whatever
        // the caller asks for next.
        .reset(ResetItem.Request);

    // The redirect is to /app/organizations/{id} -- the Organization's own page, which is where everything that
    // happens next is offered -- so the id is the last segment of the path.
    var path = location.get();
    return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
  }

  /**
   * @param organizationId The Organization.
   * @return The authorization its source holds, read back off the {@code brief_sources} row, or {@code null} if it
   *     holds none — no source, or a source whose credential has been removed.
   */
  protected static OAuthConnection connection(UUID organizationId) {
    return SourceLinkService.connection(sources.findByOrganizationId(organizationId).orElse(null));
  }

  /**
   * @param type A kind of source.
   * @return The fake standing in for that kind's host.
   */
  protected static FakeRepositoryClient fake(BriefSourceType type) {
    return type == BriefSourceType.GITHUB ? github : gitlab;
  }

  /**
   * Gives an Organization an authorization with a host, by running the real link flow with the fake host's canned
   * code exchange. The credential that results lands in the {@code brief_sources} row — the same document the
   * poller reads — so nothing here stubs out the half of the mechanism most worth exercising.
   *
   * @param type           The kind of source to connect.
   * @param organizationId The Organization to connect.
   * @return The access token now stored against the Organization.
   */
  protected String link(BriefSourceType type, UUID organizationId) {
    var result = links.link(type, organizationId, "test-code",
        "http://localhost:" + TEST_PORT + RepositorySourceController.callbackPath(type));
    assertEquals(result, SourceLinkService.LinkResult.LINKED);
    return links.accessToken(organizationId);
  }

  /**
   * {@link #link} for GitHub, which most of the suite connects.
   */
  protected String linkGitHub(UUID organizationId) {
    return link(BriefSourceType.GITHUB, organizationId);
  }

  /**
   * Posts the form the "Rebuild now" button posts, then runs the cycle the nudge it sends would have run. The
   * controller's contract ends at the redirect — it hands the work to the poller thread and returns — so the build has
   * to be driven explicitly here or every assertion that follows would race a background cycle.
   * {@code poller.enabled=false} in the test configuration is what guarantees this is the only cycle running.
   */
  protected void rebuild(UUID organizationId) {
    test.post("/app/organizations/" + organizationId + "/rebuild")
        .assertRedirect(303, "/app/organizations/" + organizationId);
    pollerService.testRun();
  }

  /**
   * Runs one real cycle and reports the status it recorded for one Organization. The status is read back off the
   * {@code brief_sources} row rather than returned by the call, which is the stronger assertion: it proves the
   * status was persisted, not merely computed.
   *
   * @param organizationId The Organization whose row to read.
   * @return The status the cycle recorded for it.
   */
  protected SourceStatus runCycle(UUID organizationId) {
    pollerService.testRun();
    return sources.findByOrganizationId(organizationId).orElseThrow().lastStatus();
  }
}
