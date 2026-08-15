/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

/**
 * {@code GET /api/v1/organization} end to end. Every {@code 200} body is asserted with
 * {@link JSONBodyAsserter#equalToFile} against a golden file under {@code src/test/json/organization} — the
 * {@code OrganizationsResponse} envelope, the element order, and every member of every Organization all have to match
 * at once, so an extra field, a leaked credential column, or a reordered pair fails rather than slipping past
 * piecewise assertions. The files carry the wire format literally; only the values seeded fresh each run — the
 * Organization ids, and the {@code updateInstant} the link flow moves — are {@code ${...}} substitution tokens,
 * supplied from the records the test created.
 */
@Test
public class OrganizationAPITest extends BaseTest {
  public JSONBodyAsserter json = new JSONBodyAsserter();

  /**
   * @param name The golden file's name.
   * @return The expected JSON file for this API, under {@code src/test/json/organization}.
   */
  private static Path expected(String name) {
    return Path.of("src/test/json/organization", name);
  }

  /**
   * No Organizations is an ordinary state — a fresh Agency — and the answer is the envelope around an empty array,
   * not a {@code 404}.
   */
  @Test
  public void anEmptyDatabaseYieldsAnEmptyArray() throws Exception {
    organizations()
        .assertStatus(200)
        .assertHeaderStartsWith("Content-Type", "application/json")
        .assertBodyAs(json, b -> b.equalToFile(expected("empty.json")));
  }

  /**
   * The caller's entitled set is still every Organization (2026-07-30 design §10.2, decision 3), in the name order
   * {@code DatabaseService.listOrganizations} defines — the same list the admin UI shows.
   */
  @Test
  public void listReturnsEveryOrganization() throws Exception {
    var alpha = insertOrganization("alpha");
    var beta = insertOrganization("beta");

    organizations()
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalToFile(expected("two-organizations.json"), "alphaId", alpha.id(), "betaId", beta.id()));
  }

  /**
   * No {@code Authorization} header at all. Answered by the OIDC middleware installed on the {@code /api} prefix,
   * so it never reaches the controller.
   */
  @Test
  public void missingTokenIsUnauthorized() {
    test.get("/api/v1/organization")
        .assertStatus(401);
  }

  /**
   * A connected Organization must serialize exactly like an unconnected one: {@code gitHubConnection} is a live
   * bearer credential and is annotated off the wire, and the golden file's whole-body equality is what asserts its
   * absence rather than something nobody checked.
   */
  @Test
  public void aConnectedOrganizationDoesNotLeakItsCredential() throws Exception {
    var organization = insertOrganization("alpha");
    linkGitHub(organization.id());

    // linkGitHub moved the row's update_instant, so the substituted value is read back rather than reused --
    // findOrganization returns the connection too, which the golden file must not (and does not) carry.
    var stored = db.findOrganization(organization.id()).orElseThrow();
    organizations()
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalToFile(expected("connected.json"), "organizationId", organization.id(), "updateInstant", stored.updateInstant()));
  }

  /**
   * A bearer token that is not a JWT the configured provider signed. Local JWKS verification rejects it without a
   * network call and the middleware answers {@code 401}.
   */
  @Test
  public void unknownTokenIsUnauthorized() {
    test.withHeader("Authorization", "Bearer nope")
        .get("/api/v1/organization")
        .assertStatus(401);
  }

  /**
   * @return The asserter for an authenticated request, for the caller to chain status and body assertions onto.
   */
  private WebTestAsserter organizations() throws Exception {
    // Cleared first rather than after: the headers this sets accumulate on the shared tester, so a second call in
    // one method would otherwise send two Authorization headers.
    test.clearRequestState();

    var tokens = apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);
    return test.withHeader("Authorization", "Bearer " + tokens.accessToken())
               .get("/api/v1/organization");
  }
}
