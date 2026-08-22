/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module java.net.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Verifies the kickstart-provisioned FusionAuth theme is applied to the hosted identity pages, and that its one
 * cross-origin dependency actually loads. The hosted login page must link the admin UI's stylesheet (the URL the
 * kickstart baked in from {@code FUSIONAUTH_APP_THEME_CSS_URL}) and carry the theme toggle plus the dark-mode boot
 * script — proving the custom advanced theme is in effect rather than FusionAuth's stock one. And the app must
 * serve {@code /static} with a Cross-Origin-Resource-Policy that lets the FusionAuth origin embed the stylesheet,
 * without weakening that policy anywhere else.
 */
@Test(groups = "integration")
public class FusionAuthThemeIntegrationTest extends BaseTest {
  @Test
  public void hostedLoginPageUsesTheAgencyTheme() throws Exception {
    var redirectURI = "http://localhost:" + TEST_PORT + main.ssrSettings.callbackPath();
    var query = "client_id=" + URLEncoder.encode(main.ssrConfig.clientId(), StandardCharsets.UTF_8)
        + "&redirect_uri=" + URLEncoder.encode(redirectURI, StandardCharsets.UTF_8)
        + "&response_type=code"
        + "&scope=" + URLEncoder.encode(String.join(" ", main.ssrConfig.scopes()), StandardCharsets.UTF_8)
        + "&state=themetest";
    var authorize = URI.create(main.ssrConfig.authorizeEndpoint() + "?" + query);

    try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
      var response = client.send(HttpRequest.newBuilder(authorize).GET().build(),
          HttpResponse.BodyHandlers.ofString());

      assertEquals(response.statusCode(), 200,
          "Expected the hosted login page, got [" + response.statusCode() + "]: [" + response.body() + "]");
      var body = response.body();
      assertTrue(body.contains("/static/css/app.css"),
          "The hosted login page does not link the admin UI's stylesheet, so the Agency theme is not in effect.");
      assertTrue(body.contains("theme-toggle"), "The hosted login page carries no theme toggle.");
      assertTrue(body.contains("https://theagencyhq.dev/js/theme-0.1.0.js"),
          "The hosted login page does not reference the website's theme-switching script.");
      // The login form itself, because the whole suite depends on it: OIDCTestFixture posts these fields on
      // every authorization-code flow, and a theme that drops one breaks every HTTP test at once.
      assertTrue(body.contains("name=\"loginId\"") && body.contains("name=\"password\""),
          "The themed login page lost the credential fields.");
    }
  }

  @Test
  public void staticAssetsAllowCrossOriginEmbedding() throws Exception {
    try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
      var stylesheet = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + TEST_PORT + "/static/css/app.css")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
      assertEquals(stylesheet.statusCode(), 200,
          "Expected the stylesheet to be served, got [" + stylesheet.statusCode() + "]");
      assertEquals(stylesheet.headers().firstValue("Cross-Origin-Resource-Policy").orElse(null), "cross-origin",
          "Assets under /static must be cross-origin embeddable so the FusionAuth hosted pages (a different "
              + "origin) can load the stylesheet.");

      var page = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + TEST_PORT + "/")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
      assertEquals(page.headers().firstValue("Cross-Origin-Resource-Policy").orElse(null), "same-origin",
          "Non-static responses must keep the strict same-origin CORP; the relaxation is scoped to /static only.");
    }
  }
}
