/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.controller.*;
import dev.theagencyhq.agency.db.*;
import dev.theagencyhq.agency.model.api.internal.*;
import dev.theagencyhq.agency.service.*;
import org.lattejava.web.Configuration;

@SuppressWarnings("resource")
public class Main {
  public static final Path BASE_DIR = Path.of("web");
  public static final int PORT = 8080;
  public static final List<String> REQUIRED_CONFIG = List.of("db.password", "db.url", "db.username", "handler.tokens");
  public final Configuration config;
  public final int port;
  public final JTETemplates templates;
  public final Web web;

  public Main() {
    this(PORT, false);
  }

  /**
   * @param port The port to listen on. The tests pass their own so a suite run cannot collide with a development
   *     server left listening on {@link #PORT} -- a collision that surfaces as every HTTP test class failing in
   *     {@code @BeforeSuite}, which reads like a broken build rather than an occupied port.
   * @param test True to layer the test configuration over the defaults.
   */
  public Main(int port, boolean test) {
    this.config = new Configuration(
        REQUIRED_CONFIG,
        Path.of(System.getProperty("user.home"), ".config", "the-agency-hq", "the-agency", "config.properties"),
        test ? Path.of("src/test/resources/config.properties") : Path.of("non-existent"),
        Path.of("src/main/resources/config.properties")
    );
    Services.initialize(config);
    this.port = port;
    this.templates = new JTETemplates(Path.of("web/templates"), Path.of("build"));
    this.web = new Web();
  }

  private static Set<String> tokens(Configuration config) {
    var tokens = Arrays.stream(config.get("handler.tokens").split(","))
                       .map(String::trim)
                       .filter(t -> !t.isEmpty())
                       .collect(Collectors.toSet());
    if (tokens.isEmpty()) {
      // A server that accepts nothing is indistinguishable at runtime from a wrong token, so fail at startup
      throw new IllegalStateException("The [handler.tokens] configuration is empty, so no Handler could ever authenticate");
    }

    return tokens;
  }

  public void close() {
    Services.shutdown();
    web.close();
  }

  public DatabaseService databaseService() {
    return Services.databaseService();
  }

  public void main() {
    var briefing = new BriefingController(Services.briefingService(), tokens(config));
    var organizations = new OrganizationController(templates);

    // addShutdownTask is what makes Services.shutdown() reachable in production at all: Web installs its own JVM
    // shutdown hook, so on SIGTERM this is what closes the HikariCP pool and the poller's scheduler instead of
    // abandoning them, possibly mid-insertBrief. Main.close() (which only tests call) also invokes it directly;
    // Services.shutdown() is idempotent and thread-safe precisely so both paths are harmless.
    web.install(SecurityHeaders.defaults())
       .addShutdownTask(Services::shutdown)
       .baseDir(BASE_DIR)
       .files("/static")
       .get("/", (_, res) -> res.sendRedirect("/app/organizations/", 303))
       .post("/api/v1/briefing", briefing::briefing, BodySupplier.of(BriefingRequestJSON::fromJSON))
       .prefix("/app", app -> {
             app.prefix("/organizations", orgs -> {
                   orgs.get("/", organizations::list);
                   orgs.get("/new", organizations::newForm);
                   orgs.post("/", organizations::create);
                   orgs.get("/{organizationId}", organizations::detail);
                   orgs.post("/{organizationId}/rebuild", organizations::rebuild);
                   orgs.get("/{organizationId}/versions/{version}", organizations::version);
                   orgs.get("/{organizationId}/versions/{version}/files/{index}", organizations::file);
                 }
             );
           }
       )
       // Loopback explicitly, NOT Web.start(int), whose default listener binds every interface. The admin UI has no
       // authentication at all (design §11), and that is only defensible while it is unreachable from off the box:
       // anyone who can reach this port can register an Organization pointing at an arbitrary local directory, make
       // the Agency run `git pull` in it, and read and download any file the resulting Brief contains.
       .start(new HTTPListenerConfiguration(InetAddress.getLoopbackAddress(), port));
  }
}
