/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.api.*;

/**
 * {@code POST /api/v1/briefing} end to end, including the whole §10.2 decision matrix — the scenarios that used to
 * drive {@code BriefingService.decide} directly with hand-built maps. The service reads the database itself now, so a
 * test of it needs real rows either way; driving the same scenario over HTTP costs nothing extra and asserts the
 * response a Handler actually receives.
 *
 * <p>Every {@code 200} is asserted as one whole {@link BriefingResponse}, parsed by the same codec the Handler
 * uses and compared by record equality. Nothing is asserted piecewise: the entitled set, the delivered Briefs, their
 * order, and every member of every Brief and its Organization all have to match at once, so an extra Brief, a dropped
 * member, or a reordered pair fails rather than slipping past assertions that only looked at the parts someone thought
 * to name. Parsing is also the well-formedness check, which is why there is no separate "is this valid JSON" test.
 */
@Test
public class BriefingAPITest extends BaseTest {
  public JSONBodyAsserter json = new JSONBodyAsserter();
  public StringBodyAsserter string = new StringBodyAsserter();
  private Organization organization;
  private Brief storedBrief;

  /**
   * @param organizationId The Organization being asserted.
   * @param version        The version the Handler claims to hold.
   * @param checksum       The checksum the Handler claims to hold.
   * @return One {@code currentVersions} entry, as the Handler would send it.
   */
  private static String orgRequest(Object organizationId, int version, String checksum) {
    return "{\"organizationId\":\"" + organizationId + "\",\"version\":" + version + ",\"checksum\":\"" + checksum + "\"}";
  }

  /**
   * The Handler still holds an Organization the Agency no longer has. Without the entitled-set comparison this would
   * answer {@code 304} and the Handler would serve that Brief forever.
   */
  @Test
  public void aDeletedOrganizationForcesA200() throws Exception {
    briefing(orgRequest(organization.id(), 1, "sum-1") + "," + orgRequest(UUID.randomUUID(), 3, "sum-gone"))
        .assertStatus(200)
        // Nothing is stale -- the only Organization that exists was asserted correctly -- so the 200 is forced
        // purely by the set comparison, and it carries no Briefs.
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of())));
  }

  @Test
  public void absentBodyIsTreatedAsAnEmptyAssertion() throws Exception {
    // Design §10.2: a request with no body at all means "I hold nothing", identical to {"currentVersions":[]}.
    // BriefingController implements that with `body == null ? List.of() : body.currentVersions()`, and every other
    // test in this class sends a body -- so that branch had no coverage at all. The assertions are deliberately the
    // same ones coldStoreReceivesTheBrief makes, because "the same result" is the whole claim.
    var tokens = apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);
    test.withHeader("Authorization", "Bearer " + tokens.accessToken())
        .post("/api/v1/briefing")
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of(storedBrief))));
  }

  /**
   * An entitled Organization with no Brief yet is not deliverable, and must not make a {@code 304} impossible for as
   * long as it stays unbuilt.
   */
  @Test
  public void anUnbuiltOrganizationDoesNotBlockA304() throws Exception {
    insertOrganization();

    briefing(orgRequest(organization.id(), 1, "sum-1"))
        .assertStatus(304);
  }

  @Test
  public void coldStoreReceivesTheBrief() throws Exception {
    briefing("")
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of(storedBrief))));
  }

  @Test
  public void currentVersionIsNotModified() throws Exception {
    briefing(orgRequest(organization.id(), 1, "sum-1"))
        .assertStatus(304)
        // 304 carries no body per HTTP -- nothing on that path should write one. Asserted as a string because an
        // empty body is not JSON and the JSON asserter would fail to parse it.
        .assertBodyAs(string, StringBodyAsserter::isEmpty);
  }

  @Test
  public void duplicateAssertionIsRejected() throws Exception {
    var entry = orgRequest(organization.id(), 1, "sum-1");

    briefing(entry + "," + entry)
        .assertStatus(400);
  }

  /**
   * The middleware's refresh path, which nothing else covers. An access token the server cannot verify, presented with
   * a refresh token, makes the middleware exchange that refresh token at the provider's token endpoint — authenticating
   * as a confidential client with HTTP Basic and {@code fusionauth.clientSecret} — and serve the request with the
   * refreshed token rather than answering {@code 401}.
   *
   * <p>Every other request in this suite carries an access token that verifies locally against the JWKS and never
   * reaches the provider mid-request. (The configured secret itself is not what this pins down: a wrong one already
   * fails the fixture's token exchange in {@code @BeforeSuite} and skips the entire suite.)
   */
  @Test
  public void expiredTokenWithARefreshTokenIsRefreshed() throws Exception {
    var tokens = apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);
    test.withHeader("Authorization", "Bearer not-a-token-the-server-can-verify")
        .withHeader("X-Refresh-Token", tokens.refreshToken())
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of(storedBrief))));
  }

  /**
   * A body that could never parse, sent with no token. The answer must be {@code 401} rather than {@code 400}: Web runs
   * prefix middleware ahead of a route's {@code BodySupplier}, so the server never parses a body it has no reason to
   * trust. Nothing else in this suite pins that ordering, and swapping it would leak the shape of the API to callers
   * who cannot authenticate.
   */
  @Test
  public void malformedBodyWithoutTokenIsUnauthorized() {
    test.withHeader("Content-Type", "application/json")
        .withBody("not json at all")
        .post("/api/v1/briefing")
        .assertStatus(401);
  }

  /**
   * No {@code Authorization} header at all. Answered by the OIDC middleware installed on the {@code /api} prefix, so it
   * never reaches the controller.
   */
  @Test
  public void missingTokenIsUnauthorized() {
    test.withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(401);
  }

  // Seeded per method, not per class: BaseTest empties the database before each one, so a @BeforeClass seed would
  // survive only until the first reset and every test after the first would find nothing.
  @BeforeMethod
  public void seed() {
    organization = insertOrganization();
    // insertBrief returns the stored Brief, which is what the API emits: DatabaseService attaches the row's
    // provenance columns on the way back out, so it carries a version and a sourceCommit the document never had.
    storedBrief = insertBrief(organization, "sum-1");
  }

  /**
   * The round trip from the {@code briefs.document} column, through {@code Brief}, and back out as JSON, with a Brief
   * that actually carries files — {@code coldStoreReceivesTheBrief} delivers an empty one, so nothing there exercises
   * file content, encodings, modes or Mission Types surviving the column. Comparing whole responses is what makes this
   * cover them: every member of every file is in the equality without being named.
   *
   * <p>The version and provenance are columns rather than members of the stored document, so this also proves
   * {@code DatabaseService} reattaches them on the way out.
   */
  @Test
  public void storedBriefSurvivesTheRoundTrip() throws Exception {
    // Replaces the seeded Brief: two versions for one Organization would make the latest ambiguous to read back.
    resetDatabase();
    organization = insertOrganization();
    var withFiles = insertBrief(organization, "sum-files", briefFile(".claude/rules/a.md", "first"), briefFile(".codex/rules/a.md", "first"));

    briefing("")
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of(withFiles))));
  }

  /**
   * ONE ordering drives both arrays. {@code BriefingService} sorts once, by the id's String form, and derives the Brief
   * order from that same sorted list — so {@code organizationIds[i]} and {@code briefs[i]} describe the same
   * Organization. Asserted with positional array comparison, because that pairing is the entire claim: if the two were
   * ever sorted by different comparators, they would disagree for any id pair whose leading hex nibble crosses the 7/8
   * boundary ({@code UUID::compareTo} reads {@code mostSigBits} as signed).
   */
  @Test
  public void twoOrganizationsPairIdsWithBriefsInOneOrdering() throws Exception {
    var organizationB = insertOrganization();
    var briefB = insertBrief(organizationB, "sum-2");

    briefing("")
        .assertStatus(200)
        // Record equality on both Lists is positional, so this asserts the pairing directly: the expected response
        // is built by sorting both arrays the same way, and any disagreement between them fails here.
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization, organizationB), List.of(storedBrief, briefB))));
  }

  /**
   * The other half of the entitled/deliverable distinction: not deliverable, but still entitled. Omitting it from
   * {@code organizationIds} would tell the Handler it had been revoked, and the Handler would tear its Location down
   * for an Organization that is merely waiting on its first build.
   */
  @Test
  public void unbuiltOrganizationsStillAppearInOrganizationIds() throws Exception {
    var unbuilt = insertOrganization();

    briefing("")
        .assertStatus(200)
        // Entitled but undeliverable: `unbuilt` is in organizationIds and absent from briefs, and comparing the
        // whole response is what makes "absent from briefs" an assertion rather than something nobody checked.
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization, unbuilt), List.of(storedBrief))));
  }

  /**
   * A bearer token that is not a JWT the configured provider signed, and no {@code X-Refresh-Token} to fall back on.
   * Local JWKS verification rejects it without a network call and the middleware answers {@code 401} — the counterpart
   * to {@link #expiredTokenWithARefreshTokenIsRefreshed}, which supplies that fallback.
   */
  @Test
  public void unknownTokenIsUnauthorized() {
    test.withHeader("Authorization", "Bearer nope")
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(401);
  }

  @Test
  public void unparseableExtraAssertionYieldsAnEmptyBriefsArray() throws Exception {
    // The real Organization is asserted exactly as stored, so it is not stale, but the unparseable second entry
    // still forces a 200 instead of a 304 (BriefingService's hasUnparseableId).
    briefing(orgRequest(organization.id(), 1, "sum-1") + "," + orgRequest("not-a-uuid", 1, "x"))
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of())));
  }

  @Test
  public void wrongChecksumResends() throws Exception {
    briefing(orgRequest(organization.id(), 1, "corrupt"))
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of(storedBrief))));
  }

  @Test
  public void wrongVersionResends() throws Exception {
    briefing(orgRequest(organization.id(), 0, "sum-old"))
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON, briefingResponse(List.of(organization), List.of(storedBrief))));
  }

  /**
   * @param currentVersions The {@code currentVersions} entries, already comma-joined; empty for "I hold nothing".
   * @return The asserter for the response, for the caller to chain status and body assertions onto.
   */
  private WebTestAsserter briefing(String currentVersions) throws Exception {
    // Cleared first rather than after: the headers this sets accumulate on the shared tester, so a second call in
    // one method would otherwise send two Authorization and two Content-Type headers.
    test.clearRequestState();

    var tokens = apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);
    return test.withHeader("Authorization", "Bearer " + tokens.accessToken())
               .withHeader("Content-Type", "application/json")
               .withBody("{\"currentVersions\":[" + currentVersions + "]}")
               .post("/api/v1/briefing");
  }
}
