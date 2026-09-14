/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.model.api.internal.*;
import org.lattejava.web.Configuration;

@SuppressWarnings("resource")
public class Main {
  public static final Path BASE_DIR = Path.of("web").toAbsolutePath();
  public static final int PORT = 8080;
  /**
   * Everything the server cannot start without. The Brief source credentials ({@code github.*}, {@code gitlab.*},
   * {@code bitbucket.*}) are deliberately not here: a kind of source is offered when its credentials are configured
   * and hidden when they are not ({@code SourceCatalog}), so a server with none configured still starts and serves
   * the Briefs it holds.
   */
  public static final List<String> REQUIRED_CONFIG = List.of("db.password", "db.url", "db.username",
      "fusionauth.apiKey", "fusionauth.baseURL", "fusionauth.clientId", "fusionauth.clientSecret",
      "fusionauth.handlerClientId", "fusionauth.handlerClientSecret", "fusionauth.issuer", "runtime.mode",
      "web.cookieEncryptionKey");
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
  private final BeanScope injector;
  private final AtomicBoolean shutdownStarted = new AtomicBoolean();
  private final OIDC<User> ssrOIDC;

  /**
   * The production entry point's constructor: the port comes from the {@code PORT} environment variable when the
   * platform assigns one (an env-and-system-property-only lookup, since the instance's layered Configuration cannot
   * exist before the instance), falling back to {@link #PORT}.
   */
  public Main() {
    this(new Configuration().getInteger("PORT", PORT), false);
  }

  /**
   * @param port The port to listen on. The tests pass their own so a suite run cannot collide with a development
   *             server left listening on {@link #PORT} -- a collision that surfaces as every HTTP test class failing
   *             in {@code @BeforeSuite}, which reads like a broken build rather than an occupied port.
   * @param test True to layer the test configuration over the defaults and build the scope under the
   *             {@link Wiring#TEST_PROFILE test profile}, which swaps the real repository host clients for the test
   *             module's fakes. The hosts are the one seam the tests have into the Agency's outbound dependencies:
   *             everything else a test drives is either in this process or a local container it can provision, where
   *             the hosts are neither.
   */
  public Main(int port, boolean test) {
    this.config = new Configuration(
        REQUIRED_CONFIG,
        Path.of(System.getProperty("user.home"), ".config", "the-agency-hq", "the-agency", "config.properties"),
        test ? Path.of("src/test/resources/config.properties") : Path.of("non-existent"),
        Path.of("src/main/resources/config.properties")
    );

    // The configuration is the one bean the scope cannot build for itself -- which files it is layered from is
    // this constructor's decision -- so it is supplied, and everything else is derived from it (see Wiring). The
    // test profile is the other thing only this constructor knows: under it the scope skips the real host clients
    // and takes the test module's fakes, which Avaje finds by itself on the module path. Building the scope is
    // what constructs every singleton, in dependency order: the database and its migrations, the FusionAuth
    // discovery behind the OIDC profiles, the poller thread.
    var builder = BeanScope.builder().bean(Configuration.class, config);
    if (test) {
      builder.profiles(Wiring.TEST_PROFILE);
    }
    this.injector = builder.build();

    this.apiConfig = injector.get(OIDCConfig.class, Wiring.API);
    this.apiOIDC = inject(Wiring.API);
    this.cookies = inject(Cookies.class);
    this.port = port;
    this.ssrConfig = injector.get(OIDCConfig.class, Wiring.SSR);
    this.ssrOIDC = inject(Wiring.SSR);
    this.ssrSettings = inject(BrowserSettings.class);
    this.web = new Web();
  }

  /**
   * Answers a deployment platform's healthcheck with a plain 200. Deliberately checks nothing: a Main that constructed
   * at all has already proven the database (migrations applied) and FusionAuth (Discovery and the JWKS fetch), so a
   * listening server is the health being asserted. Static for the same reason as {@link #missing} — it lives outside
   * both authentication prefixes and needs nothing from an instance.
   *
   * @param req The request.
   * @param res The response.
   * @throws IOException If the response cannot be written.
   */
  public static void health(HTTPRequest req, HTTPResponse res) throws IOException {
    res.setStatus(200);
    res.setHeader("Content-Type", "text/plain");
    res.getWriter().write("OK");
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
    shutdown();
    web.close();
  }

  /**
   * @param type The bean type.
   * @param <T>  The bean type.
   * @return The scope's bean of that type: the one instance of a singleton, or a fresh instance of a prototype.
   */
  public <T> T inject(Class<T> type) {
    return injector.get(type);
  }

  public void main() {
    // Installed once on the literal /app/organizations prefix, mirroring latte-java/app's GroupSecurity: a route
    // without an {organizationId} attribute passes through, and every route with one requires the signed-in user
    // to hold a membership row in that Organization. The role gates below layer on top of it per route.
    var organizationSecurity = inject(OrganizationSecurity.class);
    var isActiveMember = organizationSecurity.hasRole(Role.CONTRIBUTOR, Role.OWNER);
    var isOwner = organizationSecurity.hasRole(Role.OWNER);

    // addShutdownTask is what makes closing the scope reachable in production at all: Web installs its own JVM
    // shutdown hook, so on SIGTERM this is what stops the poller and closes the HikariCP pool instead of abandoning
    // them, possibly mid-build. Main.close() (which only tests call) also invokes it directly; shutdown() is
    // idempotent and thread-safe precisely so both paths are harmless.
    web.addShutdownTask(this::shutdown)
       // Every route handler resolves its controller from the scope on each request. The controllers, the
       // services under them, and the repositories under those are prototypes, so each request gets a graph of
       // its own and nothing is shared between requests but the singletons -- the database, the OIDC profiles,
       // the poller.
       .injector(injector::get)
       .baseDir(BASE_DIR)
       // The static resources are used cross-origin: the FusionAuth theme -- a different origin -- links the
       // admin UI's stylesheet, and the browser refuses that under the default same-origin
       // Cross-Origin-Resource-Policy. Installed before files() so the relaxed headers are what a served asset
       // carries, exactly as latte-java/app does it; everything after the file handler still gets the defaults
       // below.
       .install(new FilteredMiddleware("/static", SecurityHeaders.empty().crossOriginResourcePolicy("cross-origin")))
       .files("/static")
       // After the file handler, so a request for an asset never consumes the messages queued for the page that
       // loads it. Global, so a message queued before any redirect reaches the page the browser lands on.
       .install(new FlashMessages())
       // script-src widens beyond 'self' for exactly one origin: the theme-switching script is loaded from the
       // website, the same copy the FusionAuth theme loads, so all three surfaces share one script.
       .install(SecurityHeaders.defaults()
                               .contentSecurityPolicy(CSP.defaults().scriptSrc(CSP.SELF, "https://theagencyhq.dev")))
       // An unmatched path renders the same styled 404 the controllers use, rather than the empty-body default
       // that a browser turns into its own error page or a blank one.
       .missingHandler(Main::missing)

       // /login, /oidc/return, /logout, /oidc/logout-return. A middleware rather than four routes, and installed at
       // the root because the browser reaches them directly and none of them can be behind the gate they exist to
       // satisfy. Everything else falls through to the routes below.
       .install(OIDC.sessionEndpoints(ssrConfig, ssrSettings))
       .get("/", (_, res) -> res.sendRedirect("/app/organizations/", 303))
       // Outside both authentication prefixes, because Railway's healthcheck gates every deploy on a plain 200 --
       // the root route's 303 reads as a failure to it.
       .get("/health", Main::health)

       // The authentication middleware is installed on the /api prefix rather than per route, so every API route
       // added later is authenticated by construction -- there is no per-route opt-in to forget. Web runs prefix
       // middleware before the route's BodySupplier, so an unauthenticated request carrying a malformed body is a
       // 401 and never a 400: the server does not parse a body it has no reason to trust.
       .prefix("/api", api ->
           api.install(apiOIDC.authenticated())
              .get("/v1/organization", web.inject(OrganizationAPIController.class, OrganizationAPIController::list))
              .post("/v1/briefing", web.inject(BriefingController.class, BriefingController::briefing),
                  BodySupplier.of(BriefingRequestJSON::fromJSON))
       )
       // Installed on the literal /app prefix for the same reason the API's is installed on /api: every page added
       // under it is gated by construction. An unauthenticated request is not an error here but a redirect -- the
       // browser is sent to /login, and the callback returns it to the page it asked for.
       .prefix("/app", app -> {
             app.install(ssrOIDC.authenticated())
                // The nav's Account link. A redirect rather than a templated URL so the FusionAuth origin and
                // client id stay in configuration instead of being threaded through every page render. FusionAuth
                // hosts the account pages; its SSO session (established at login) signs the user straight in. The
                // trailing slash matters: FusionAuth serves account management at /account/ and bounces /account
                // to its root landing page instead.
                .get("/account", (_, res) -> res.sendRedirect(
                    config.get("fusionauth.baseURL") + "/account/?client_id=" + config.get("fusionauth.clientId"), 303));

             // One OAuth prefix per kind of source, inside the gate, deliberately. Granting an Organization a
             // credential is an operator action, so an unauthenticated visitor must never be able to start a
             // connection or land a callback that stores one. Every kind is routed whether or not this server is
             // configured for it -- the controller answers 404 for one that is not -- so the route table is one
             // shape for every deployment. GitHub alone has the install pair: its return is a GitHub redirect that
             // must find the picker to go back to, exactly as the callbacks find the Sources page. GitLab and
             // Bitbucket read whatever the authorizing account can, so they have no such trip.
             for (var type : BriefSourceType.values()) {
               app.prefix("/oauth/" + type.slug(), oauth -> {
                     oauth.get("/start", web.inject(RepositorySourceController.class, (c, req, res) -> c.start(type, req, res)));
                     oauth.get("/callback", web.inject(RepositorySourceController.class, (c, req, res) -> c.callback(type, req, res)));
                     if (type == BriefSourceType.GITHUB) {
                       oauth.get("/install", web.inject(RepositorySourceController.class, RepositorySourceController::install));
                       oauth.get("/setup", web.inject(RepositorySourceController.class, RepositorySourceController::setup));
                     }
                   }
               );
             }

             app.prefix("/organizations", orgs -> {
                   orgs.install(organizationSecurity);
                   orgs.get("/", web.inject(OrganizationController.class, OrganizationController::list));
                   orgs.get("/new", web.inject(OrganizationController.class, OrganizationController::newForm));
                   orgs.post("/", web.inject(OrganizationController.class, OrganizationController::create));
                   // Base-gated only (any membership row, PENDING included): the Organization's page is where
                   // an invited user finds Accept and Decline, so it cannot demand what accepting grants.
                   orgs.get("/{organizationId}", web.inject(OrganizationController.class, OrganizationController::detail));
                   // Owner-only: the selection decides what every Handler in the Organization is served.
                   orgs.get("/{organizationId}/agents", web.inject(OrganizationController.class, OrganizationController::agentsForm), isOwner);
                   orgs.post("/{organizationId}/agents", web.inject(OrganizationController.class, OrganizationController::updateAgents), isOwner);
                   // Owner-only: the Sources page is where a source is connected, and the pickers under it swap
                   // the Organization's source repository.
                   orgs.get("/{organizationId}/sources", web.inject(OrganizationController.class, OrganizationController::sources), isOwner);
                   for (var type : BriefSourceType.values()) {
                     orgs.get("/{organizationId}/sources/" + type.slug(), web.inject(RepositorySourceController.class, (c, req, res) -> c.repositoryForm(type, req, res)), isOwner);
                     orgs.post("/{organizationId}/sources/" + type.slug(), web.inject(RepositorySourceController.class, (c, req, res) -> c.connectRepository(type, req, res)), isOwner);
                   }
                   // Any ACTIVE member: rebuilding produces a new version but changes no configuration.
                   orgs.post("/{organizationId}/rebuild", web.inject(OrganizationController.class, OrganizationController::rebuild), isActiveMember);
                   orgs.get("/{organizationId}/versions/{version}", web.inject(OrganizationController.class, OrganizationController::version));
                   orgs.get("/{organizationId}/versions/{version}/files/{index}", web.inject(OrganizationController.class, OrganizationController::file));

                   // Member administration is owner-only. Accept, decline, and leave always act on the signed-in
                   // user's own row — no {userId} in the path — and stay base-gated: accept and decline must
                   // reach the controller for PENDING invitees, and a PENDING leaver just deletes their own
                   // invitation, same as declining.
                   orgs.prefix("/{organizationId}/members", members ->
                       members.get("/", web.inject(MembershipController.class, MembershipController::list), isOwner)
                              .get("/invite", web.inject(MembershipController.class, MembershipController::inviteForm), isOwner)
                              .post("/invite", web.inject(MembershipController.class, MembershipController::invite), isOwner)
                              .post("/accept", web.inject(MembershipController.class, MembershipController::accept))
                              .post("/decline", web.inject(MembershipController.class, MembershipController::decline))
                              .get("/leave", web.inject(MembershipController.class, MembershipController::leaveForm))
                              .post("/leave", web.inject(MembershipController.class, MembershipController::leave))
                              .get("/{userId}/remove", web.inject(MembershipController.class, MembershipController::removeForm), isOwner)
                              .post("/{userId}/remove", web.inject(MembershipController.class, MembershipController::remove), isOwner)
                              .get("/{userId}/role", web.inject(MembershipController.class, MembershipController::changeRoleForm), isOwner)
                              .post("/{userId}/role", web.inject(MembershipController.class, MembershipController::changeRole), isOwner));
                 }
             );
           }
       )
       // Loopback explicitly outside production, NOT Web.start(int), whose default listener binds every interface.
       // Authentication is no longer what keeps this here -- both /app and /api require a FusionAuth token now --
       // but the transport is: there is no local TLS listener, and Cookies marks the session cookies Secure only on
       // an https request (Cookies#isSecureScheme). Off the loopback interface, over plain http, every admin
       // session cookie would travel in the clear.
       //
       // Production (Railway) is the TLS listener that bind was waiting on: the edge terminates TLS and proxies
       // over the project's private network carrying X-Forwarded-Proto, which the request scheme honors -- so the
       // cookies are Secure there, and the bind widens to every interface because the proxy's connections must be
       // accepted. runtime.mode is required configuration with a closed value set (MembershipService rejects
       // anything but development/production at startup), so a typo cannot silently open the wide bind.
       .start("production".equals(config.get("runtime.mode"))
           ? new HTTPListenerConfiguration(port)
           : new HTTPListenerConfiguration(InetAddress.getLoopbackAddress(), port));
  }

  // The two OIDC profiles share a type and are told apart by name.
  private OIDC<User> inject(String profile) {
    return injector.get(OIDC.class, profile);
  }

  /**
   * Closes the scope, which stops the poller (its {@code @PreDestroy}) and closes the database (its bean's destroy
   * method), in reverse order of construction. Idempotent and safe to call from more than one thread at once,
   * because it genuinely is called that way: Web runs its shutdown tasks from {@code closeServer()}, which is
   * reached both from {@code Web.close()} and from the JVM shutdown hook Web installs for itself, and the tests
   * add a second JVM hook of their own — at JVM exit those hooks run concurrently.
   */
  private void shutdown() {
    if (shutdownStarted.compareAndSet(false, true)) {
      injector.close();
    }
  }
}
