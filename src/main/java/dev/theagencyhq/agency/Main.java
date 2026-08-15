/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency;

import module java.base;
import module dev.theagencyhq.agency;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.model.api.internal.*;
import org.lattejava.web.Configuration;

@SuppressWarnings("resource")
public class Main {
  public static final Path BASE_DIR = Path.of("web");
  public static final int PORT = 8080;
  public static final List<String> REQUIRED_CONFIG = List.of("db.password", "db.url", "db.username",
      "fusionauth.apiKey", "fusionauth.baseURL", "fusionauth.clientId", "fusionauth.clientSecret",
      "fusionauth.handlerClientId", "fusionauth.handlerClientSecret", "fusionauth.issuer", "github.appName",
      "github.clientId", "github.clientSecret", "web.cookieEncryptionKey");
  /**
   * Static rather than per-instance so {@link #missing} can render without a Main, but built from the same two constant
   * paths an instance always used.
   */
  public static final JTETemplates TEMPLATES = new JTETemplates(Path.of("web/templates"), Path.of("build"));
  /**
   * Public so the tests can drive a real authorization-code flow with the configuration the server is actually
   * validating against, rather than a second copy of it that could drift. The same goes for {@link #ssrConfig} and
   * {@link #ssrSettings}, which the browser-side fixture needs as a pair.
   */
  public final OIDCConfig apiConfig;
  public final Configuration config;
  public final Cookies cookies;
  public final int port;
  public final OIDCConfig ssrConfig;
  public final BrowserSettings ssrSettings;
  public final Web web;
  private final OIDC<User> apiOIDC;
  private final OIDC<User> ssrOIDC;

  public Main() {
    this(PORT, false, null);
  }

  /**
   * @param port   The port to listen on. The tests pass their own so a suite run cannot collide with a development
   *               server left listening on {@link #PORT} -- a collision that surfaces as every HTTP test class failing
   *               in {@code @BeforeSuite}, which reads like a broken build rather than an occupied port.
   * @param test   True to layer the test configuration over the defaults.
   * @param github The GitHub client, or null for the real one. The one seam the tests have into the Agency's only
   *               outbound dependency: everything else a test drives is either in this process or a local container it
   *               can provision, where GitHub is neither.
   */
  public Main(int port, boolean test, GitHubClient github) {
    this.config = new Configuration(
        REQUIRED_CONFIG,
        Path.of(System.getProperty("user.home"), ".config", "the-agency-hq", "the-agency", "config.properties"),
        test ? Path.of("src/test/resources/config.properties") : Path.of("non-existent"),
        Path.of("src/main/resources/config.properties")
    );
    Services.initialize(config, github);
    this.cookies = Cookies.encryptionKeys(Base64.getDecoder().decode(config.get("web.cookieEncryptionKey")));

    // Two profiles over two FusionAuth Applications, mirroring `latte-java/app`: a browser profile for the admin UI
    // and an API profile for the Handler. Both are confidential clients -- only a server ever holds either secret --
    // and both translate through UserService, so a route under either gets the caller as a User rather than a bag
    // of claims.
    //
    // Separate Applications, not one shared between them, because the audience check is the boundary: each profile
    // requires its own client id in the token's `aud`, so a Handler's token cannot open the admin UI and an admin
    // session cannot call the Briefing API. One Application would silently make those interchangeable.
    //
    // Every builder call here reaches FusionAuth: build() runs OIDC Discovery against the issuer and OIDC.ssr/api
    // fetch the JWKS. The Agency therefore does not start while FusionAuth is down, which is the honest outcome --
    // a server that booted without the ability to validate a single token would reject everyone while looking
    // healthy. Nothing in the request path calls FusionAuth after this (validateAccessToken defaults to true, so a
    // token is verified locally against the JWKS), so a later outage costs nothing until a token needs refreshing.
    this.ssrConfig = OIDCConfig.builder()
                               .clientId(config.get("fusionauth.clientId"))
                               .clientSecret(config.get("fusionauth.clientSecret"))
                               // FusionAuth does not advertise introspection in its OpenID configuration, so
                               // Discovery cannot fill this in and it is built from the base URL by hand.
                               .introspectionEndpoint(URI.create(config.get("fusionauth.baseURL") + "/oauth2/introspect"))
                               .issuer(config.get("fusionauth.issuer"))
                               .build();
    this.ssrSettings = BrowserSettings.builder()
                                      .postLoginPage("/app/organizations/")
                                      // `app` lands a signed-out user on its marketing site. The Agency's does not
                                      // exist yet -- theagencyhq.dev does not resolve -- so pointing there would
                                      // hand every sign-out a browser error page. "/" bounces through the gate to
                                      // the login screen, which is the right place to leave someone who just left.
                                      .postLogoutPage("/")
                                      .build();
    this.ssrOIDC = OIDC.ssr(ssrConfig, ssrSettings, UserService::toUser);

    this.apiConfig = OIDCConfig.builder()
                               .clientId(config.get("fusionauth.handlerClientId"))
                               .clientSecret(config.get("fusionauth.handlerClientSecret"))
                               .introspectionEndpoint(URI.create(config.get("fusionauth.baseURL") + "/oauth2/introspect"))
                               .issuer(config.get("fusionauth.issuer"))
                               .build();
    this.apiOIDC = OIDC.api(apiConfig, UserService::toUser);
    this.port = port;
    this.web = new Web();
  }

  /**
   * Renders the admin UI's not-found page with a 404 status. One method for both kinds of miss: Web invokes it for a
   * path that matches no route, and the browser-facing controllers call it for a path whose Organization, version, or
   * file does not exist. Static because the unmatched-path case also fires outside the authenticated {@code /app}
   * prefix, where there is no signed-in user — which is why {@code 404.jte} stands alone instead of using the layout,
   * whose chrome requires a viewer.
   *
   * @param req The request.
   * @param res The response.
   * @throws IOException If the response cannot be written.
   */
  public static void missing(HTTPRequest req, HTTPResponse res) throws IOException {
    res.setStatus(404);
    TEMPLATES.html("pages/404.jte", req, res, Map.of());
  }

  public void close() {
    Services.shutdown();
    web.close();
  }

  public void main() {
    var briefing = new BriefingController(apiOIDC, Services.briefingService());
    var github = new GitHubController(cookies, ssrOIDC);
    var members = new MembershipController(ssrOIDC, TEMPLATES);
    var organizations = new OrganizationController(
        "https://github.com/apps/" + config.get("github.appName") + "/installations/new", ssrOIDC, TEMPLATES);
    var organizationAPI = new OrganizationAPIController(apiOIDC, Services.databaseService());

    // Installed once on the literal /app/organizations prefix, mirroring latte-java/app's GroupSecurity: a route
    // without an {organizationId} attribute passes through, and every route with one requires the signed-in user
    // to hold a membership row in that Organization. The role gates below layer on top of it per route.
    var organizationSecurity = new OrganizationSecurity(ssrOIDC);
    var isActiveMember = organizationSecurity.hasRole(Role.CONTRIBUTOR, Role.OWNER);
    var isOwner = organizationSecurity.hasRole(Role.OWNER);

    // addShutdownTask is what makes Services.shutdown() reachable in production at all: Web installs its own JVM
    // shutdown hook, so on SIGTERM this is what closes the HikariCP pool and the poller's scheduler instead of
    // abandoning them, possibly mid-insertBrief. Main.close() (which only tests call) also invokes it directly;
    // Services.shutdown() is idempotent and thread-safe precisely so both paths are harmless.
    web.install(SecurityHeaders.defaults())
       .addShutdownTask(Services::shutdown)
       .baseDir(BASE_DIR)
       .files("/static")
       // An unmatched path renders the same styled 404 the controllers use, rather than the empty-body default
       // that a browser turns into its own error page or a blank one.
       .missingHandler(Main::missing)

       // /login, /oidc/return, /logout, /oidc/logout-return. A middleware rather than four routes, and installed at
       // the root because the browser reaches them directly and none of them can be behind the gate they exist to
       // satisfy. Everything else falls through to the routes below.
       .install(OIDC.sessionEndpoints(ssrConfig, ssrSettings))
       .get("/", (_, res) -> res.sendRedirect("/app/organizations/", 303))

       // The authentication middleware is installed on the /api prefix rather than per route, so every API route
       // added later is authenticated by construction -- there is no per-route opt-in to forget. Web runs prefix
       // middleware before the route's BodySupplier, so an unauthenticated request carrying a malformed body is a
       // 401 and never a 400: the server does not parse a body it has no reason to trust.
       .prefix("/api", api ->
           api.install(apiOIDC.authenticated())
              .get("/v1/organization", organizationAPI::list)
              .post("/v1/briefing", briefing::briefing, BodySupplier.of(BriefingRequestJSON::fromJSON))
       )
       // Installed on the literal /app prefix for the same reason the API's is installed on /api: every page added
       // under it is gated by construction. An unauthenticated request is not an error here but a redirect -- the
       // browser is sent to /login, and the callback returns it to the page it asked for.
       .prefix("/app", app -> {
             app.install(ssrOIDC.authenticated())
                // Inside the gate, deliberately. Granting an Organization a GitHub credential is an operator
                // action, so an unauthenticated visitor must never be able to start a connection or land a
                // callback that stores one.
                .prefix("/oauth/github", oauth -> {
                      oauth.get("/start", github::start);
                      oauth.get("/callback", github::callback);
                    }
                )
                .prefix("/organizations", orgs -> {
                      orgs.install(organizationSecurity);
                      orgs.get("/", organizations::list);
                      orgs.get("/new", organizations::newForm);
                      orgs.post("/", organizations::create);
                      // Base-gated only (any membership row, PENDING included): the Organization's page is where
                      // an invited user finds Accept and Decline, so it cannot demand what accepting grants.
                      orgs.get("/{organizationId}", organizations::detail);
                      // Owner-only: these swap the Organization's source repository.
                      orgs.get("/{organizationId}/connect", organizations::connect, isOwner);
                      orgs.post("/{organizationId}/connect", organizations::connectSource, isOwner);
                      // Any ACTIVE member: rebuilding produces a new version but changes no configuration.
                      orgs.post("/{organizationId}/rebuild", organizations::rebuild, isActiveMember);
                      orgs.get("/{organizationId}/versions/{version}", organizations::version);
                      orgs.get("/{organizationId}/versions/{version}/files/{index}", organizations::file);

                      // Member administration is owner-only. Accept, decline, and leave always act on the signed-in
                      // user's own row — no {userId} in the path — and stay base-gated: accept and decline must
                      // reach the controller for PENDING invitees, and a PENDING leaver just deletes their own
                      // invitation, same as declining.
                      orgs.prefix("/{organizationId}/members", membersRoutes ->
                          membersRoutes.get("/", members::list, isOwner)
                                       .get("/invite", members::inviteForm, isOwner)
                                       .post("/invite", members::invite, isOwner)
                                       .post("/accept", members::accept)
                                       .post("/decline", members::decline)
                                       .get("/leave", members::leaveForm)
                                       .post("/leave", members::leave)
                                       .get("/{userId}/remove", members::removeForm, isOwner)
                                       .post("/{userId}/remove", members::remove, isOwner)
                                       .get("/{userId}/role", members::changeRoleForm, isOwner)
                                       .post("/{userId}/role", members::changeRole, isOwner));
                    }
                );
           }
       )
       // Loopback explicitly, NOT Web.start(int), whose default listener binds every interface. Authentication is
       // no longer what keeps this here -- both /app and /api require a FusionAuth token now -- but the transport
       // is: there is no TLS listener, and Cookies marks the session cookies Secure only on an https request
       // (Cookies#isSecureScheme). Off the loopback interface, over plain http, every admin session cookie would
       // travel in the clear. The bind widens when there is a TLS listener to widen it onto.
       .start(new HTTPListenerConfiguration(InetAddress.getLoopbackAddress(), port));
  }
}
