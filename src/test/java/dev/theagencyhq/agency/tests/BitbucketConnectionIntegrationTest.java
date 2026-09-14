/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The Bitbucket handshake: every test in {@link RepositoryConnectionBaseTest}, run against Bitbucket, plus what is
 * particular to Bitbucket — an authorization request with no scope, a source that describes itself as a repository
 * on bitbucket.org, and a picker with no install trip.
 */
@Test(groups = "integration")
public class BitbucketConnectionIntegrationTest extends RepositoryConnectionBaseTest {
  /**
   * The authorization request asks for a code and names no scope: Bitbucket's permissions are declared on the
   * consumer, and it refuses none but takes none on the request either.
   */
  @Test
  public void startAsksForACodeAndNoScope() {
    var organizationId = createOrganization("bitbucket-scope-" + UUID.randomUUID());

    test.get(startPath() + "?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          assertTrue(location.contains("&response_type=code"), location);
          assertFalse(location.contains("scope="), location);
        });
  }

  /**
   * Bitbucket has no installation step, so its picker never offers one: an account that can read no repository is
   * told to fix that on Bitbucket, and a populated picker has no "find more" trip.
   */
  @Test
  public void thePickerHasNoInstallTrip() {
    var organizationId = createOrganization("bitbucket-no-install-" + UUID.randomUUID());
    link(type(), organizationId);

    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("cannot read any repository")
                                    .doesNotContain("/app/oauth/github/install")
                                    .doesNotContain("Use this repository"));

    bitbucket.add("acme", "briefs");
    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Use this repository")
                                    .doesNotContain("Find more repositories")
                                    .doesNotContain("/app/oauth/github/install"));

    // And the GitHub-only routes do not exist under Bitbucket's prefix.
    test.get("/app/oauth/bitbucket/install?organizationId=" + organizationId).assertStatus(404);
    test.get("/app/oauth/bitbucket/setup?state=anything").assertStatus(404);
  }

  /**
   * A registered Bitbucket source stores the repository's full name as its identity, spelled as Bitbucket spells it
   * and unique case-insensitively, and describes itself for the admin UI as a repository on bitbucket.org.
   */
  @Test
  public void theSourceDescribesItselfAsARepositoryOnBitbucket() {
    bitbucket.add("Acme", "Briefs").putFile("rules/a.md", "first\n");
    var organizationId = createOrganization("bitbucket-describes-" + UUID.randomUUID());
    link(type(), organizationId);

    test.withFormField("repository", "Acme/Briefs")
        .withFormField("branch", "main")
        .post(pickerPath(organizationId))
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.source(), "Acme/Briefs");
    var config = (BitbucketConfig) source.config();
    assertEquals(config.repository(), "Acme/Briefs");
    assertEquals(config.fullName(), "Acme/Briefs");
    assertEquals(sources.findBySource(BriefSourceType.BITBUCKET, "acme/briefs").orElseThrow().id(), source.id());
    assertEquals(config.url(), "https://bitbucket.org/Acme/Briefs");
    assertEquals(config.details(), List.of(new SourceDetail("Repository", "Acme/Briefs", config.url()),
        new SourceDetail("Branch", "main", null)));
    assertEquals(runCycle(organizationId), SourceStatus.OK);

    var row = database.dsl()
                .resultQuery("SELECT source_config->>'type', source_config->>'repository', source_config->>'branch' "
                    + "FROM brief_sources WHERE id = ?", source.id())
                .fetchOne();
    assertEquals(row.get(0, String.class), "BITBUCKET");
    assertEquals(row.get(1, String.class), "Acme/Briefs");
    assertEquals(row.get(2, String.class), "main");
  }

  @Override
  protected String authorizeURL() {
    return BitbucketHTTPClient.AUTHORIZE_URL;
  }

  @Override
  protected BriefSourceType type() {
    return BriefSourceType.BITBUCKET;
  }
}
