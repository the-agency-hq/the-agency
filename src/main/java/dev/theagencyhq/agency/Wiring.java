/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;
import module org.lattejava.web;

import dev.theagencyhq.agency.model.User;
import jakarta.inject.Named;
import org.jooq.*;
import org.lattejava.fusionauth.*;
import org.lattejava.http.server.*;
import org.lattejava.web.Configuration;

/**
 * The beans that are built rather than annotated: everything the application needs from outside its own classes,
 * derived from the {@link Configuration} that {@link Main} supplies to the scope. These are the singletons. Every class
 * of the Agency's own — controller, service, repository — is a {@code @Prototype} wired by its constructor, built
 * afresh for each request that reaches it, so this is the whole of the hand-written wiring and the whole of what
 * requests share.
 *
 * <p>Two OIDC profiles over two FusionAuth Applications, mirroring {@code latte-java/app}: a browser profile for the
 * admin UI and an API profile for the Handler. Both are confidential clients — only a server ever holds either secret —
 * and both translate through {@code UserService}, so a route under either gets the caller as a {@link User} rather than
 * a bag of claims. Separate Applications, not one shared between them, because the audience check is the boundary: each
 * profile requires its own client id in the token's {@code aud}, so a Handler's token cannot open the admin UI and an
 * admin session cannot call the Briefing API. One Application would silently make those interchangeable. The two are
 * told apart by name, {@code ssr} and {@code api}.
 *
 * <p>Every OIDC builder call here reaches FusionAuth: {@code build()} runs OIDC Discovery against the issuer and
 * {@code OIDC.ssr}/{@code api} fetch the JWKS. The Agency therefore does not start while FusionAuth is down, which is
 * the honest outcome — a server that booted without the ability to validate a single token would reject everyone while
 * looking healthy. Nothing in the request path calls FusionAuth after this ({@code validateAccessToken} defaults to
 * true, so a token is verified locally against the JWKS), so a later outage costs nothing until a token needs
 * refreshing.
 */
@Factory
public class Wiring {
  public static final String API = "api";
  public static final String SSR = "ssr";

  @Bean
  @Named(API)
  public OIDCConfig apiConfig(Configuration config) {
    return OIDCConfig.builder()
                     .clientId(config.get("fusionauth.handlerClientId"))
                     .clientSecret(config.get("fusionauth.handlerClientSecret"))
                     .introspectionEndpoint(URI.create(config.get("fusionauth.baseURL") + "/oauth2/introspect"))
                     .issuer(config.get("fusionauth.issuer"))
                     .build();
  }

  @Bean
  @Named(API)
  public OIDC<User> apiOIDC(@Named(API) OIDCConfig config) {
    return OIDC.api(config, UserService::toUser);
  }

  @Bean
  public Cookies cookies(Configuration config) {
    return Cookies.encryptionKeys(Base64.getDecoder().decode(config.get("web.cookieEncryptionKey")));
  }

  /**
   * The database: opened once, here, and closed when the scope closes. The pool must exist before anything that queries
   * it, which the repositories' dependency on {@link #dsl} guarantees.
   */
  @Bean(destroyMethod = "close")
  public Database database(Configuration config) {
    return Database.initialize(config);
  }

  @Bean
  public DSLContext dsl(Database database) {
    return database.dsl();
  }

  @Bean
  public FusionAuthClient fusionAuth(Configuration config) {
    return new FusionAuthClient(config.get("fusionauth.apiKey"), config.get("fusionauth.baseURL"));
  }

  /**
   * The real GitHub client. The tests supply a fake to the scope instead, which takes this bean's place: the repository
   * hosts are the Agency's only outbound dependencies on services it does not own, and a suite that reached the real
   * api.github.com would need a live GitHub App, a live installation, and a network — and would still be measuring
   * GitHub rather than the Agency. Built whether or not GitHub is configured, from whatever credentials there are:
   * {@code SourceCatalog} decides whether the kind is offered, and an unconfigured client is never asked anything.
   */
  @Bean
  public GitHubClient gitHubClient(Configuration config) {
    return new GitHubHTTPClient(config.get("github.clientId", ""), config.get("github.clientSecret", ""));
  }

  /**
   * The real GitLab client, against {@code gitlab.baseURL} — gitlab.com unless a self-managed instance is named. Faked
   * in tests for the same reason as {@link #gitHubClient}.
   */
  @Bean
  public GitLabClient gitLabClient(Configuration config) {
    return new GitLabHTTPClient(config.get("gitlab.baseURL", SourceCatalog.GITLAB_DEFAULT_BASE_URL),
        config.get("gitlab.clientId", ""), config.get("gitlab.clientSecret", ""));
  }

  @Bean
  @Prototype
  public HTTPRequest request() {
    return RequestContext.request();
  }

  @Bean
  @Prototype
  public HTTPResponse response() {
    return RequestContext.response();
  }

  @Bean
  @Named(SSR)
  public OIDCConfig ssrConfig(Configuration config) {
    return OIDCConfig.builder()
                     .clientId(config.get("fusionauth.clientId"))
                     .clientSecret(config.get("fusionauth.clientSecret"))
                     // FusionAuth does not advertise introspection in its OpenID configuration, so Discovery
                     // cannot fill this in and it is built from the base URL by hand.
                     .introspectionEndpoint(URI.create(config.get("fusionauth.baseURL") + "/oauth2/introspect"))
                     .issuer(config.get("fusionauth.issuer"))
                     .build();
  }

  @Bean
  @Named(SSR)
  public OIDC<User> ssrOIDC(@Named(SSR) OIDCConfig config, BrowserSettings settings) {
    return OIDC.ssr(config, settings, UserService::toUser);
  }

  @Bean
  public BrowserSettings ssrSettings() {
    return BrowserSettings.builder()
                          .postLoginPage("/app/organizations/")
                          // `app` lands a signed-out user on its marketing site. The Agency's does not exist yet
                          // -- theagencyhq.dev does not resolve -- so pointing there would hand every sign-out a
                          // browser error page. "/" bounces through the gate to the login screen, which is the
                          // right place to leave someone who just left.
                          .postLogoutPage("/")
                          .build();
  }

  @Bean
  public JTETemplates templates() {
    return Main.TEMPLATES;
  }
}
