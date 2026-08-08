/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.agency.*;
import dev.theagencyhq.agency.db.*;
import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.api.*;
import dev.theagencyhq.agency.service.*;
import dev.theagencyhq.agency.util.*;

import static org.testng.Assert.*;

/**
 * The base for every test that needs the application: the server, the database, or the service singletons. Pure unit
 * tests — path mapping, JSON shapes, SemVer, the Git wrapper — do not extend this and should not, since booting a
 * server to test a regex is pure cost.
 *
 * <p>One {@link Main} for the whole suite, started in {@code @BeforeSuite}. TestNG runs classes sequentially, and
 * only one of them can bind the port, so per-class startup would be both wasteful and impossible.
 *
 * <p>{@link #TEST_PORT} is deliberately not {@link Main#PORT}. A development server left running on 8080 used to
 * make every HTTP test class fail in configuration with "one of the listeners threw an exception", which reads like a
 * broken build rather than an occupied port.
 *
 * <p>{@code @BeforeMethod} truncates the database, so every test starts from empty and no test has to bookkeep
 * what it created. {@code organizations} is the only root: {@code brief_sources} and {@code briefs} both cascade from
 * it, so deleting it clears the graph.
 */
public abstract class BaseTest {
  /**
   * The FusionAuth user every test authenticates as, provisioned by {@code src/main/fusionauth/kickstart}.
   */
  public static final String TEST_EMAIL = "admin@theagencyhq.dev";
  /**
   * A fixed instant for every seeded row. Both instant columns are {@code BIGINT} epoch millis and Organization
   * truncates to that precision, so a fixed millisecond value round-trips through the database unchanged — which
   * lets a test compare a seeded record with one read back, field for field.
   */
  public static final Instant TEST_INSTANT = Instant.ofEpochMilli(1_700_000_000_000L);
  public static final String TEST_PASSWORD = "password";
  public static final int TEST_PORT = 8081;
  /**
   * A loopback redirect the Handler Application accepts. Its registered redirect carries a wildcard port because the
   * real Handler binds an ephemeral one; nothing listens on this port during a suite run, since the fixture reads
   * the authorization code off the redirect rather than following it.
   */
  public static final String TEST_REDIRECT_URI = "http://127.0.0.1:8888/callback";
  /**
   * Where the browser profile's challenge sends an unauthenticated visitor. The default {@code BrowserSettings}
   * login path, and the {@code Location} an admin UI request without cookies has to carry.
   */
  public static final String LOGIN_PATH = "/login";
  /**
   * The Briefing API's login fixture, bound to the Handler Application. Distinct from {@link #ssrOIDC} because the
   * two profiles are two OAuth clients: a token from one carries the wrong {@code aud} for the other, which is the
   * whole point of keeping the Applications separate.
   */
  public static OIDCTestFixture apiOIDC;
  public static BriefingService briefingService;
  public static DatabaseService db;
  public static Main main;
  public static OrganizationService organizationService;
  public static PollerService pollerService;
  /**
   * The admin UI's login fixture, bound to the Agency Application.
   */
  public static OIDCTestFixture ssrOIDC;
  public static WebTest test = new WebTest(TEST_PORT);

  @AfterSuite
  public static void afterSuite() {
    if (main != null) {
      main.close();
    }
  }

  @BeforeSuite
  public static void beforeSuite() throws Exception {
    main = new Main(TEST_PORT, true);
    main.main();
    briefingService = Services.briefingService();
    db = Services.databaseService();
    organizationService = Services.organizationService();
    pollerService = Services.pollerService();
    apiOIDC = new OIDCTestFixture(test, main.apiConfig);
    ssrOIDC = new OIDCTestFixture(test, main.ssrConfig, main.ssrSettings);
  }

  public static void deleteDirectory(Path directory) throws IOException {
    if (directory != null && Files.isDirectory(directory)) {
      try (var walk = Files.walk(directory)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
          if (!p.toFile().delete()) {
            throw new IllegalStateException("Unable to prune directory [" + directory + "]");
          }
        });
      }
    }
  }

  /**
   * A Brief file with a real checksum over its own content, so a Brief built from these is internally consistent
   * the way one out of the builder is.
   *
   * @param path    The Brief-relative output path.
   * @param content The file's text.
   * @return The file.
   */
  public static BriefFile briefFile(String path, String content) {
    return new BriefFile(path, BriefFile.DEFAULT_ENCODING, BriefFile.DEFAULT_MODE, content,
        Checksums.sha256Hex(content.getBytes(StandardCharsets.UTF_8)), List.of("web"));
  }

  /**
   * Builds the response the Briefing API must produce, in the canonical order §10.2 specifies: both arrays sorted
   * by the Organization id's String form, so {@code organizationIds[i]} and {@code briefs[i]} describe the same
   * Organization. Restating that rule here is the point — comparing a whole expected response against the whole
   * actual one asserts the ordering, the entitled set, and every member of every Brief in a single equality.
   *
   * @param entitled Every Organization the Handler may receive Briefs for.
   * @param briefs   The Briefs that should be delivered, in any order.
   * @return The expected response.
   */
  public static BriefingResponse briefingResponse(List<Organization> entitled, List<Brief> briefs) {
    return new BriefingResponse(
        entitled.stream().map(o -> o.id().toString()).sorted().toList(),
        briefs.stream().sorted(Comparator.comparing(b -> b.organization().id().toString())).toList());
  }

  /**
   * Inserts a Brief for an Organization, straight to the database and without going near the builder or a Git
   * repository. A test that cares what the Briefing API does with a stored Brief does not also want to care how
   * one is produced.
   *
   * @param organization The owning Organization.
   * @param checksum     The content checksum, which most callers just need to be distinguishable.
   * @param files        The Brief's files, if the test cares what they are.
   * @return The stored Brief, carrying the version the insert assigned.
   */
  public static Brief insertBrief(Organization organization, String checksum, BriefFile... files) {
    return db.insertBrief(new Brief(checksum, organization, null, List.of(files), "abc", TEST_INSTANT));
  }

  /**
   * Inserts an Organization under a generated name. Names are unique case-insensitively, and the database is shared
   * by the whole suite, so a generated name is the only kind that cannot collide with another test's.
   *
   * @return The inserted Organization.
   */
  public static Organization insertOrganization() {
    return insertOrganization("org-" + UUID.randomUUID());
  }

  /**
   * @param name The Organization's display name.
   * @return The inserted Organization.
   */
  public static Organization insertOrganization(String name) {
    var organization = new Organization(UUID.randomUUID(), name, TEST_INSTANT, TEST_INSTANT);
    db.insertOrganization(organization);
    return organization;
  }

  /**
   * Empties every table. Public and static so the one class that opts out of the automatic reset can still call it
   * where it wants one.
   */
  public static void resetDatabase() {
    // ON DELETE CASCADE carries brief_sources and briefs with it, so this is the whole graph. Raw SQL because
    // DatabaseService deliberately exposes no bulk delete on its production API.
    db.dsl().execute("DELETE FROM organizations");
  }

  /**
   * Ends whatever session the method established, as {@code latte-java/app}'s own base class does. A test signs in
   * once at the top and every request it makes after that is signed in; this is what keeps the session from
   * outliving the method and quietly authenticating the next one.
   *
   * <p>Load-bearing, not decoration: without it {@code anAnonymousVisitorIsSentToLogin} and
   * {@code everyAdminPathIsGated} both pass on a session some earlier method left behind.
   *
   * <p>Named for what it does rather than {@code afterMethod} because {@link AdminUITest} declares one of those: a
   * subclass method with the same signature would override this one, and the override would silently skip it.
   */
  @AfterMethod(alwaysRun = true)
  public void logoutAfterMethod() {
    ssrOIDC.logout();
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    resetDatabase();

    // The tester is shared by the whole suite and accumulates headers, form fields, and a body until something
    // clears them. Clearing here means a method starts from nothing, exactly as it starts with an empty database,
    // so no chain has to end in a reset() purely to protect whatever runs next. Twelve tests fail without it.
    test.clearRequestState();
  }

  protected void commit(Path root, String message) throws Exception {
    run(root, "git", "add", "-A");
    run(root, "git", "commit", "-q", "-m", message);
  }

  protected Path createBinarySourceRepository(byte[] content) throws Exception {
    var dir = Files.createDirectories(Path.of("build/test/admin-ui-" + UUID.randomUUID()).toAbsolutePath());
    Files.writeString(dir.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    Files.createDirectories(dir.resolve("rules"));
    Files.write(dir.resolve("rules/" + "logo.bin"), content);
    // initRepository already commits, so committing again here fails with "nothing to commit".
    initRepository(dir);
    return dir;
  }

  protected UUID createOrganization(String name, String path) {
    var location = new AtomicReference<String>();
    test.withFormField("name", name)
        .withFormField("path", path)
        .post("/app/organizations/")
        .assertStatus(303)
        .assertResponse(r -> location.set(r.headers().firstValue("Location").orElseThrow()))
        // Request only, never Cookies: the caller signed in once and the rest of its requests still need that
        // session. What has to go is this method's own form fields, which would otherwise ride along on whatever
        // the caller asks for next.
        .reset(ResetItem.Request);
    return UUID.fromString(location.get().substring(location.get().lastIndexOf('/') + 1));
  }

  protected Path createSourceRepository(String ruleContent) throws Exception {
    var dir = Files.createDirectories(Path.of("build/test/admin-ui-" + UUID.randomUUID()).toAbsolutePath());
    Files.writeString(dir.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    Files.createDirectories(dir.resolve("rules"));
    Files.writeString(dir.resolve("rules/a.md"), ruleContent);
    initRepository(dir);
    return dir;
  }

  protected void initRepository(Path root) throws Exception {
    run(root, "git", "init", "-q", "-b", "main");
    run(root, "git", "config", "user.email", "test@theagencyhq.dev");
    run(root, "git", "config", "user.name", "Test");
    run(root, "git", "config", "commit.gpgsign", "false");
    commit(root, "initial");
  }

  /**
   * Posts the form the "Rebuild now" button posts, then runs the cycle the nudge it sends would have run. The
   * controller's contract ends at the redirect — it hands the work to the poller thread and returns — so the build has
   * to be driven explicitly here or every assertion that follows would race a background cycle.
   * {@code poller.enabled=false} in the test configuration is what guarantees this is the only cycle running.
   */
  protected void rebuild(UUID organizationId) {
    test.post("/app/organizations/" + organizationId + "/rebuild")
        .assertRedirect(303, "/app/organizations/" + organizationId);
    Services.pollerService().testRun();
  }

  protected void run(Path directory, String... command) throws Exception {
    var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(process.waitFor(), 0, "[" + String.join(" ", command) + "] -> [" + output + "]");
  }

  protected Path write(Path root, String relative, String content) throws IOException {
    var path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }
}
