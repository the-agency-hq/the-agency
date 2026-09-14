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
 * The GitLab handshake: every test in {@link RepositoryConnectionBaseTest}, run against GitLab, plus what is
 * particular to GitLab — the authorization URL's scope, nested group paths, and a picker with no install trip.
 */
@Test(groups = "integration")
public class GitLabConnectionIntegrationTest extends RepositoryConnectionBaseTest {
  /**
   * A GitLab project's path can carry a nested group. The whole path is the repository's name everywhere: what the
   * picker lists, what the form posts, what the row stores as its identity, and what the URL is built from.
   */
  @Test
  public void aNestedGroupPathIsOneRepositoryName() {
    gitlab.add("acme/platform", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createOrganization("gitlab-nested-" + UUID.randomUUID());
    link(type(), organizationId);

    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("value=\"acme/platform/briefs\""));

    test.withFormField("repository", "acme/platform/briefs")
        .withFormField("branch", "main")
        .post(pickerPath(organizationId))
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.source(), "acme/platform/briefs");
    var config = (GitLabConfig) source.config();
    assertEquals(config.project(), "acme/platform/briefs");
    assertEquals(config.baseURL(), SourceCatalog.GITLAB_DEFAULT_BASE_URL);
    assertEquals(sources.findBySource(BriefSourceType.GITLAB, "ACME/Platform/Briefs").orElseThrow().id(), source.id());
    assertEquals(config.url(), "https://gitlab.com/acme/platform/briefs");
    assertEquals(config.details(), List.of(new SourceDetail("Project", "acme/platform/briefs", config.url()),
        new SourceDetail("Branch", "main", null)));
    assertEquals(runCycle(organizationId), SourceStatus.OK);
  }

  /**
   * GitLab has no installation step, so its picker never offers one: an account that is a member of no project is
   * told to fix that on GitLab, and a populated picker has no "find more" trip.
   */
  @Test
  public void thePickerHasNoInstallTrip() {
    var organizationId = createOrganization("gitlab-no-install-" + UUID.randomUUID());
    link(type(), organizationId);

    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("not a member of any project")
                                    .doesNotContain("/app/oauth/github/install")
                                    .doesNotContain("Use this repository"));

    gitlab.add("acme", "briefs");
    test.get(pickerPath(organizationId))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Use this repository")
                                    .doesNotContain("Find more repositories")
                                    .doesNotContain("/app/oauth/github/install"));

    // And the GitHub-only routes do not exist under GitLab's prefix.
    test.get("/app/oauth/gitlab/install?organizationId=" + organizationId).assertStatus(404);
    test.get("/app/oauth/gitlab/setup?state=anything").assertStatus(404);
  }

  /**
   * The authorization request names the {@code read_api} scope and asks for a code, which a GitLab OAuth
   * application requires where a GitHub App infers both.
   */
  @Test
  public void startAsksForTheReadAPIScope() {
    var organizationId = createOrganization("gitlab-scope-" + UUID.randomUUID());

    test.get(startPath() + "?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          assertTrue(location.contains("&response_type=code"), location);
          assertTrue(location.contains("&scope=read_api"), location);
        });
  }

  @Override
  protected String authorizeURL() {
    return SourceCatalog.GITLAB_DEFAULT_BASE_URL + "/oauth/authorize";
  }

  @Override
  protected BriefSourceType type() {
    return BriefSourceType.GITLAB;
  }
}
