/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.Main;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Drives the admin UI entirely through HTTP, posting forms rather than calling {@code OrganizationService} or
 * {@code PollerService} directly, so the controller's own validation and routing are what is under test.
 */
@Test(groups = "integration")
public class AdminUIIntegrationTest extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();

  @Test
  public void binaryFileRendersSizeAndDownloadsRawBytes() {
    // 0xFF is never a valid UTF-8 leading byte, so BriefBuilder's strict decoder falls back to base64 -- this
    // exercises the binary branch of the file page (size + download link, no inline preview) and the download
    // response itself, neither of which the text-file scenarios below touch.
    var binary = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02, (byte) 0x80};
    github.add("acme", "briefs").putBytes("rules/logo.bin", binary);
    var organizationId = createConnectedOrganization("admin-ui-binary-" + UUID.randomUUID());

    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId + "/versions/1")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("base64"));

    test.get("/app/organizations/" + organizationId + "/versions/1/files/0")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("binary and cannot be previewed").contains("?download=true"));

    // The download link reuses the current request's own path plus ?download=true rather than embedding raw bytes
    // in the HTML page, so the raw-bytes round trip is a separate request against that same URL.
    test.get("/app/organizations/" + organizationId + "/versions/1/files/0?download=true")
        .assertStatus(200)
        .assertHeader("Content-Disposition", "attachment; filename=\"logo.bin\"")
        .assertHeader("Content-Type", "application/octet-stream")
        .assertResponse(r -> assertEquals(r.body(), binary));
  }

  /**
   * A connection that dies after registration — revoked on GitHub's side — brings the warning back to the
   * Organization's page once a poll has discovered it, because reconnecting is the same operation wherever the
   * authorization went.
   */
  @Test
  public void aRevokedConnectionBringsTheWarningBack() {
    github.add("acme", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createConnectedOrganization("admin-ui-revoked-" + UUID.randomUUID());
    rebuild(organizationId);

    github.revokeAll();
    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("not connected to GitHub")
                                    .contains("/app/oauth/github/start?organizationId=" + organizationId));
  }

  /**
   * Before any GitHub authorization exists, the Organization's own page carries the warning and the button that
   * starts the authorization — it is where the create form lands the operator — and the repository picker is
   * unreachable: it would have nothing to populate itself from, so it sends the operator back to the page that
   * offers the connection.
   */
  @Test
  public void anUnconnectedOrganizationWarnsOnItsPageAndThePickerRedirectsThere() {
    var organizationId = createOrganization("admin-ui-unconnected-" + UUID.randomUUID());

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("not connected to GitHub")
                                    .contains("/app/oauth/github/start?organizationId=" + organizationId)
                                    .doesNotContain("Use this repository"));

    test.get("/app/organizations/" + organizationId + "/connect")
        .assertRedirect(303, "/app/organizations/" + organizationId);
  }

  /**
   * Once connected, the picker is populated from GitHub and each option carries its repository's default branch,
   * which is the contract {@code /static/js/connect.js} reads to pre-fill the branch field.
   */
  @Test
  public void connectListsTheRepositoriesGitHubOffers() {
    github.add("acme", "briefs").defaultBranch("trunk");
    github.add("acme", "other");
    var organizationId = createOrganization("admin-ui-picker-" + UUID.randomUUID());
    linkGitHub(organizationId);

    test.get("/app/organizations/" + organizationId + "/connect")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("value=\"acme/briefs\" data-branch=\"trunk\"")
                                    .contains("value=\"acme/other\" data-branch=\"main\"")
                                    .contains("Use this repository"));
  }

  /**
   * An operator who has authorized the App but installed it nowhere has an empty picker, which must not render as
   * an empty select they can submit — the only useful control is the one that sends them to install it.
   */
  @Test
  public void connectSendsAnOperatorWithNoInstallationsToInstallTheApp() {
    var organizationId = createOrganization("admin-ui-noinstall-" + UUID.randomUUID());
    linkGitHub(organizationId);

    test.get("/app/organizations/" + organizationId + "/connect")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Install the GitHub app")
                                    .contains("https://github.com/apps/")
                                    .doesNotContain("Use this repository"));
  }

  @Test
  public void createsAnOrganizationConnectsItAndShowsItsVersions() {
    github.add("acme", "briefs").putFile("rules/a.md", "first\n");
    var name = "admin-ui-" + UUID.randomUUID();

    test.get("/app/organizations/new")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("action=\"/app/organizations/\"").contains("method=\"post\""));

    var organizationId = createConnectedOrganization(name);

    // Connecting nudges the poller, but the nudge is asynchronous and the suite's poller thread is switched off, so
    // the cycle is driven explicitly here exactly as the "Rebuild now" button's would be.
    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("/organizations/" + organizationId + "/versions/1")
                                    .contains("acme/briefs")
                                    .contains("https://github.com/acme/briefs")
                                    // A healthy connection must not warn -- the warning is the disconnected
                                    // state's, and showing it here would train operators to ignore it.
                                    .doesNotContain("not connected to GitHub"));

    test.get("/app/organizations/" + organizationId + "/versions/1")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(".claude/rules/a.md").contains("r--------"));

    // Files are sorted by path, and ".claude/..." sorts before ".codex/...", so the claude copy is index 0.
    test.get("/app/organizations/" + organizationId + "/versions/1/files/0")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(".claude/rules/a.md").contains("first"));
  }

  @Test
  public void detailRendersTheOrganizationIdWithACopyButton() {
    // The id is the one value on this page a developer has to transcribe by hand -- it goes into a Location's
    // agent-location.json -- so it renders whether or not the Organization has ever built, and it renders next to
    // a copy button carrying the id itself. Asserting data-copy rather than the button's styling ties the test to
    // the contract /static/js/copy.js reads, which is the part that would actually break.
    var organizationId = createOrganization("admin-ui-id-" + UUID.randomUUID());

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Organization id")
                                    .contains("data-copy=\"" + organizationId + "\"")
                                    .contains("No source is registered"));
  }

  @Test
  public void fileContentIsEscapedWhenRendered() {
    // The Brief source repository is authored elsewhere and its content is never sanitized before being stored, so
    // this proves the version and file pages escape it on the way out rather than trusting it -- the one place an
    // $unsafe{...} slip would turn into stored XSS.
    github.add("acme", "briefs").putFile("rules/a.md", "<script>alert(1)</script>\n");
    var organizationId = createConnectedOrganization("admin-ui-xss-" + UUID.randomUUID());

    rebuild(organizationId);

    test.get("/app/organizations/" + organizationId + "/versions/1/files/0")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("&lt;script&gt;").doesNotContain("<script>alert(1)</script>"));
  }

  @Test
  public void listingRendersTheLatestVersionAndSourceErrors() {
    // Nothing else in the suite renders the listing page at all -- every other admin-UI test goes straight to a
    // detail, version, or file page -- so this is the only coverage of the query behind it and of the error cells.
    var repository = github.add("acme", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createConnectedOrganization("admin-ui-listing-" + UUID.randomUUID());

    rebuild(organizationId);

    // The version cell is fed by latestBriefVersions(), a different SQL statement from the one the Briefing API
    // uses. Asserting the cell and the repository rather than their markup: every element on this page carries
    // Tailwind classes, so matching a bare tag would break on any styling change rather than on a behaviour change.
    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(">1</td>").contains("acme/briefs"));

    // Break the source and re-poll, so lastError is populated too.
    repository.removeFile("the-agency-hq-settings.json");
    rebuild(organizationId);

    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("BUILD_FAILED").contains("Error:"));
  }

  /**
   * A malformed id is handled by {@code OrganizationSecurity} before any controller runs, and its answer is the
   * same silent redirect every failed membership check gets — never a 404 that would confirm what exists.
   */
  @Test
  public void malformedOrganizationIdRedirectsToTheListing() {
    test.get("/app/organizations/not-a-uuid")
        .assertRedirect(303, "/app/organizations/");
    test.get("/app/organizations/not-a-uuid/connect")
        .assertRedirect(303, "/app/organizations/");
    test.post("/app/organizations/not-a-uuid/rebuild")
        .assertRedirect(303, "/app/organizations/");
    test.get("/app/organizations/not-a-uuid/versions/1")
        .assertRedirect(303, "/app/organizations/");
    test.get("/app/organizations/not-a-uuid/versions/1/files/0")
        .assertRedirect(303, "/app/organizations/");
  }

  @Test
  public void malformedVersionAndIndexAreNotFound() {
    github.add("acme", "briefs").putFile("rules/a.md", "first\n");
    var organizationId = createConnectedOrganization("admin-ui-404-" + UUID.randomUUID());

    rebuild(organizationId);

    // Non-integer and out-of-range version.
    test.get("/app/organizations/" + organizationId + "/versions/not-a-number")
        .assertStatus(404);
    test.get("/app/organizations/" + organizationId + "/versions/999")
        .assertStatus(404);

    // Non-integer, negative, and out-of-range file index against the real version 1 (which has exactly 2 files).
    test.get("/app/organizations/" + organizationId + "/versions/1/files/not-a-number")
        .assertStatus(404);
    test.get("/app/organizations/" + organizationId + "/versions/1/files/-1")
        .assertStatus(404);
    test.get("/app/organizations/" + organizationId + "/versions/1/files/999")
        .assertStatus(404);
  }

  /**
   * A path that matches no route at all renders {@link Main#missing}'s styled not-found page rather than the
   * framework's empty-body 404, which a browser replaces with its own error page or a blank one. An Organization
   * that does not exist is a different case now that memberships gate the pages: {@code OrganizationSecurity}
   * answers it with the same silent redirect a non-member gets, so a probe cannot tell absent from forbidden.
   */
  @Test
  public void unmatchedPathsRenderTheNotFoundPageAndMissingOrganizationsRedirect() {
    // No route matches at the root, which also means no OIDC gate: the page renders for an anonymous visitor.
    test.get("/nonexistent")
        .assertStatus(404)
        .assertBodyAs(string, b -> b.contains("Page not found"));

    test.get("/app/organizations/" + UUID.randomUUID())
        .assertRedirect(303, "/app/organizations/");
  }

  /**
   * The reason the repository is verified at registration rather than at the first poll: rejected here, the
   * operator reads why on the form that caused it. Accepted here, the same failure surfaces as a
   * {@code BUILD_FAILED} on a detail page they have no reason to open, arbitrarily later.
   */
  @Test
  public void rejectsARepositoryThatIsNotABriefSource() {
    github.add("acme", "app").removeFile("the-agency-hq-settings.json");
    var name = "admin-ui-invalid-" + UUID.randomUUID();
    var organizationId = createOrganization(name);
    linkGitHub(organizationId);

    test.withFormField("repository", "acme/app")
        .withFormField("branch", "main")
        .post("/app/organizations/" + organizationId + "/connect")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("is not a Brief source repository"))
        .reset(ResetItem.Request);

    assertTrue(db.findSource(organizationId).isEmpty());
  }

  @Test
  public void rejectsAnEmptyName() {
    test.withFormField("name", "  ")
        .post("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("A name is required."))
        .reset(ResetItem.Request);

    assertEquals(db.listOrganizations().size(), 0);
  }

  /**
   * {@code /} is outside the gated {@code /app} prefix and redirects for everyone; the page it points at is what
   * then demands a session. {@link AdminUIAuthenticationIntegrationTest#everyAdminPathIsGated} covers the anonymous side of
   * that, since this class signs in before every method.
   */
  @Test
  public void rootRedirectsToTheListing() {
    test.get("/")
        .assertRedirect(303, "/app/organizations/");
  }

  // Every page in this class is behind the gate, so the session is established once here rather than at the top of
  // each method. One authorization-code flow per test, and BaseTest ends it afterwards.
  @BeforeMethod
  public void signIn() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
  }

  /**
   * Walks the whole registration through HTTP: name the Organization, authorize GitHub, pick {@code acme/briefs}.
   * The GitHub authorization is the one step that cannot be a request — it is an OAuth round trip through
   * github.com — so it goes through the link service, exactly as the real callback does.
   *
   * @param name The Organization's display name.
   * @return Its id.
   */
  private UUID createConnectedOrganization(String name) {
    var organizationId = createOrganization(name);
    linkGitHub(organizationId);

    test.withFormField("repository", "acme/briefs")
        .withFormField("branch", "main")
        .post("/app/organizations/" + organizationId + "/connect")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    return organizationId;
  }
}
