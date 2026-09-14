/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The GitHub handshake: every test in {@link RepositoryConnectionBaseTest}, run against GitHub, plus the trip only
 * GitHub has — installing the App on an account and returning to the picker.
 */
@Test(groups = "integration")
public class GitHubConnectionIntegrationTest extends RepositoryConnectionBaseTest {
  /**
   * An App configured to request user authorization during installation never uses its setup URL: GitHub runs the
   * OAuth flow after the install and returns to the callback with the code, {@code installation_id}, and
   * {@code setup_action} together. The authorization is stored as any other -- but the operator was installing, so
   * they land on the picker, not the Sources page.
   */
  @Test
  public void aCallbackFromAnInstallStoresTheCredentialAndReturnsToThePicker() {
    var organizationId = createOrganization("github-install-callback-" + UUID.randomUUID());
    var state = startInstall(organizationId);
    github.add("acme", "just-installed");

    test.get(callbackPath() + "?code=the-code&installation_id=42&setup_action=install&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources/github");

    assertNotNull(connection(organizationId));
    test.get("/app/organizations/" + organizationId + "/sources/github")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("installed")).contains("acme/just-installed"));
  }

  @Test
  public void aCallbackFromAnInstallRequestSaysItIsPending() {
    var organizationId = createOrganization("github-install-callback-request-" + UUID.randomUUID());
    var state = startInstall(organizationId);

    test.get(callbackPath() + "?code=the-code&installation_id=42&setup_action=request&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources/github");
  }

  @Test
  public void installIs404ForAnUnknownOrganization() {
    test.get("/app/oauth/github/install?organizationId=" + UUID.randomUUID())
        .assertStatus(404);
    test.get("/app/oauth/github/install")
        .assertStatus(404);
  }

  /**
   * Sending the operator to install the App carries a state, and GitHub hands that state to the App's setup URL
   * untouched -- which is the only way the return can know which picker to go back to. Same cookie, same rules as
   * the OAuth start.
   */
  @Test
  public void installSendsTheBrowserToGitHubAndRemembersTheState() {
    var organizationId = createOrganization("github-install-" + UUID.randomUUID());

    var cookie = new AtomicReference<String>();
    test.get("/app/oauth/github/install?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          assertTrue(location.startsWith("https://github.com/apps/" + main.config.get("github.appName")
              + "/installations/new?state="), location);
          assertFalse(location.endsWith("?state="), location);

          cookie.set(r.headers()
                      .allValues("Set-Cookie")
                      .stream()
                      .filter(c -> c.startsWith(RepositorySourceController.STATE_COOKIE + "="))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No state cookie: " + r.headers().allValues("Set-Cookie"))));
        });

    assertTrue(cookie.get().contains("Path=" + RepositorySourceController.COOKIE_PATH), cookie.get());
    assertTrue(cookie.get().contains("SameSite=Lax"), cookie.get());
  }

  @Test
  public void setupForADeletedOrganizationReturnsToTheListing() {
    var organizationId = createOrganization("github-setup-deleted-" + UUID.randomUUID());
    var state = startInstall(organizationId);
    organizationService.delete(organizationId);

    test.get(RepositorySourceController.SETUP_PATH + "?installation_id=42&setup_action=install&state=" + state)
        .assertRedirect(303, "/app/organizations/");
  }

  /**
   * An operator without the rights to install on an account can only ask its admins to. GitHub still returns to
   * the setup URL, with {@code setup_action=request}, and the picker has to say that nothing has changed yet
   * rather than present the same list as the outcome.
   */
  @Test
  public void setupForAnInstallRequestSaysItIsPending() {
    github.add("acme", "briefs");
    var organizationId = createOrganization("github-setup-request-" + UUID.randomUUID());
    linkGitHub(organizationId);
    var state = startInstall(organizationId);

    test.get(RepositorySourceController.SETUP_PATH + "?installation_id=42&setup_action=request&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources/github");

    test.get("/app/organizations/" + organizationId + "/sources/github")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("install_requested")));
  }

  /**
   * The point of the whole pair: come back from GitHub and land on the picker, which lists what the credential can
   * see now. The repository is registered with the fake only after the trip begins, standing in for the installation
   * the operator just made -- so the listing on return proves the picker asked GitHub again rather than serving a
   * list from before.
   */
  @Test
  public void setupReturnsToThePickerWhichListsTheNewRepository() {
    var organizationId = createOrganization("github-setup-" + UUID.randomUUID());
    linkGitHub(organizationId);
    var state = startInstall(organizationId);
    github.add("acme", "just-installed");

    test.get(RepositorySourceController.SETUP_PATH + "?installation_id=42&setup_action=install&state=" + state)
        .assertRedirect(303, "/app/organizations/" + organizationId + "/sources/github");

    test.get("/app/organizations/" + organizationId + "/sources/github")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages().get("installed")).contains("acme/just-installed"));
  }

  @Test
  public void setupWithTheWrongStateReturnsToTheListing() {
    var organizationId = createOrganization("github-setup-forged-" + UUID.randomUUID());
    startInstall(organizationId);

    test.get(RepositorySourceController.SETUP_PATH + "?installation_id=42&setup_action=install&state=not-the-nonce")
        .assertRedirect(303, "/app/organizations/");
  }

  /**
   * GitHub sends every install of the App to the setup URL, including one begun on github.com with no picker
   * waiting for it. Not an error, so no status: just the listing, the one page that is always somewhere to go.
   */
  @Test
  public void setupWithoutAStateCookieReturnsToTheListing() {
    test.get(RepositorySourceController.SETUP_PATH + "?installation_id=42&setup_action=install&state=anything")
        .assertRedirect(303, "/app/organizations/");
  }

  @Override
  protected String authorizeURL() {
    return GitHubHTTPClient.AUTHORIZE_URL;
  }

  @Override
  protected BriefSourceType type() {
    return BriefSourceType.GITHUB;
  }

  /**
   * Runs {@code /install} and returns the state it put in the install URL, leaving the matching cookie in the
   * shared jar so a setup return can be made against it.
   */
  private String startInstall(UUID organizationId) {
    var state = new AtomicReference<String>();
    test.get("/app/oauth/github/install?organizationId=" + organizationId)
        .assertStatus(302)
        .assertResponse(r -> {
          var location = r.headers().firstValue("Location").orElseThrow();
          state.set(location.substring(location.indexOf("?state=") + "?state=".length()));
        });
    return state.get();
  }
}
