/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.database;
import module org.testng;

// Single-type imports where TestNG exports a class of the same simple name, and for jOOQ, whose module exports
// its own Path and Comparator.
import java.nio.file.Files;
import java.sql.Connection;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.lattejava.web.Configuration;

import static org.testng.Assert.*;

/**
 * Every shipped migration, applied one at a time to a scratch database, against data rather than an empty schema.
 *
 * <p>The suite's own database is always at the newest version, so this builds one from nothing and walks the
 * migrations in order. Before each step the tables hold rows in every shape the step will touch, inserted with the
 * schema exactly as the previous step left it — which is what a production database holds on the day the migration
 * runs. After each step those rows are read back and compared, column for column, with what the migration should
 * have made of them, and every table the step did not touch is proven untouched. Where a step changes a document the
 * application reads, the rows are also read through the repository and codec the application uses, over a plain
 * jOOQ context on the scratch connection, so nothing migrates ahead of the step under test.
 *
 * <p>One test method per migration, chained with {@code dependsOnMethods} so they run in order and a failure at one
 * step skips the rest instead of failing them all for the same reason. The last method proves the walk was
 * complete: the shipped directory has nothing left to apply, so a migration added without a step here fails the
 * test rather than slipping through unexamined.
 */
@Test
public class MigrationTest {
  private static final String DATABASE = "the_agency_migration_test";
  private static final Path MIGRATIONS = Path.of("src/main/resources/db");
  // Seeded at 0.1.0: the four shapes an Organization and its source could be in when 0.3.0 moves the credential.
  private static final UUID CONNECTED_AND_REGISTERED = UUID.randomUUID();
  private static final UUID CONNECTED_AND_REGISTERED_SOURCE = UUID.randomUUID();
  private static final UUID CONNECTED_ONLY = UUID.randomUUID();
  private static final UUID NEITHER = UUID.randomUUID();
  private static final UUID REGISTERED_ONLY = UUID.randomUUID();
  private static final UUID REGISTERED_ONLY_SOURCE = UUID.randomUUID();
  // Seeded at 0.2.0: an Organization with an Agent selection, which 0.3.0 has to carry across untouched.
  private static final UUID NARROWED = UUID.randomUUID();
  private static final UUID NARROWED_SOURCE = UUID.randomUUID();
  // Seeded at 0.4.0: an Organization for the kind that migration admits.
  private static final UUID LATEST = UUID.randomUUID();
  private static final UUID INVITEE_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private final Map<String, List<Map<String, String>>> before = new HashMap<>();
  private Configuration config;
  private Path scratch;
  private Path steps;

  @AfterClass
  public void afterClass() throws Exception {
    if (scratch != null) {
      try (var files = Files.walk(scratch)) {
        files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  /**
   * An empty scratch database, and an empty directory the migrations are copied into one at a time. The Migrator
   * applies whatever it finds in a directory, so handing it the files one by one is what makes the steps steps.
   */
  @BeforeClass
  public void beforeClass() throws Exception {
    var suite = new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties")
    );
    var url = suite.get("db.url");
    var server = url.substring(0, url.lastIndexOf('/') + 1);

    // A fresh database each run: the point is to start from nothing.
    try (var connection = DriverManager.getConnection(server + "postgres", suite.get("db.username"), suite.get("db.password"));
         var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
      statement.execute("CREATE DATABASE " + DATABASE);
    }

    // Configuration takes files and the first definition wins, so the scratch URL goes in a file of its own ahead
    // of the suite's.
    scratch = Files.createTempDirectory("the-agency-migration");
    var override = scratch.resolve("config.properties");
    Files.writeString(override, "db.url=" + server + DATABASE + "\n");
    config = new Configuration(List.of("db.password", "db.url", "db.username"), override,
        Path.of("src/test/resources/config.properties"), Path.of("src/main/resources/config.properties"));
    steps = Files.createDirectories(scratch.resolve("steps"));
  }

  /**
   * The initial schema, on an empty database. Then every table gets rows: the four Organizations whose shapes 0.3.0
   * has to handle — connected and registered, connected only, registered only (the credential lapsed and was
   * cleared), and neither — with their memberships, their sources, and a version history. 0.2.0 touches every one
   * of the Organizations.
   */
  @Test
  public void applies010() throws Exception {
    assertEquals(apply("0.1.0"), List.of("0.1.0"));

    try (var connection = connect()) {
      assertEquals(tables(connection), List.of("brief_sources", "briefs", "members", "organizations", "versions"));
      assertEquals(columns(connection, "organizations"), List.of("github_access_expiration", "github_access_token",
          "github_login", "github_refresh_expiration", "github_refresh_token", "id", "insert_instant", "name",
          "update_instant"));

      execute(connection,
          "INSERT INTO organizations (id, name, github_login, github_access_token, github_access_expiration, "
              + "github_refresh_token, github_refresh_expiration, insert_instant, update_instant) VALUES ('"
              + CONNECTED_AND_REGISTERED + "', 'Acme', 'octocat', 'gho_access', 1700000001000, 'ghr_refresh', "
              + "1700000002000, 1700000000000, 1700000003000)",
          "INSERT INTO organizations (id, name, github_login, github_access_token, insert_instant, update_instant) "
              + "VALUES ('" + CONNECTED_ONLY + "', 'Initech', 'hubot', 'gho_forever', 1700000004000, 1700000005000)",
          "INSERT INTO organizations (id, name, insert_instant, update_instant) VALUES ('" + REGISTERED_ONLY
              + "', 'Globex', 1700000006000, 1700000007000)",
          "INSERT INTO organizations (id, name, insert_instant, update_instant) VALUES ('" + NEITHER
              + "', 'Umbrella', 1700000008000, 1700000009000)",

          "INSERT INTO members (organization_id, user_id, role, state, invited_by, invited_at, joined_at) VALUES ('"
              + CONNECTED_AND_REGISTERED + "', '" + OWNER_ID + "', 'OWNER', 'ACTIVE', NULL, NULL, 1700000000000)",
          "INSERT INTO members (organization_id, user_id, role, state, invited_by, invited_at, joined_at) VALUES ('"
              + CONNECTED_AND_REGISTERED + "', '" + INVITEE_ID + "', 'CONTRIBUTOR', 'PENDING', '" + OWNER_ID
              + "', 1700000003000, NULL)",
          "INSERT INTO members (organization_id, user_id, role, state, invited_by, invited_at, joined_at) VALUES ('"
              + REGISTERED_ONLY + "', '" + OWNER_ID + "', 'OWNER', 'ACTIVE', NULL, NULL, 1700000006000)",

          "INSERT INTO brief_sources (id, organization_id, owner, repository, branch, last_built_commit, "
              + "last_polled_instant, last_status, last_error, insert_instant, update_instant) VALUES ('"
              + CONNECTED_AND_REGISTERED_SOURCE + "', '" + CONNECTED_AND_REGISTERED + "', 'Acme', 'Briefs', 'main', "
              + "'abc123', 1700000010000, 'OK', NULL, 1700000000000, 1700000010000)",
          "INSERT INTO brief_sources (id, organization_id, owner, repository, branch, last_built_commit, "
              + "last_polled_instant, last_status, last_error, insert_instant, update_instant) VALUES ('"
              + REGISTERED_ONLY_SOURCE + "', '" + REGISTERED_ONLY + "', 'Globex', 'Rules', 'develop', NULL, "
              + "1700000011000, 'NOT_CONNECTED', 'lapsed', 1700000006000, 1700000011000)",

          "INSERT INTO briefs (id, organization_id, version, checksum, document, source_commit, insert_instant) "
              + "VALUES (gen_random_uuid(), '" + CONNECTED_AND_REGISTERED + "', 1, 'c1', '{\"files\": []}', "
              + "'aaa111', 1700000001000)",
          "INSERT INTO briefs (id, organization_id, version, checksum, document, source_commit, insert_instant) "
              + "VALUES (gen_random_uuid(), '" + CONNECTED_AND_REGISTERED + "', 2, 'c2', '{\"files\": [1]}', "
              + "'abc123', 1700000010000)");

      remember(connection);
    }
  }

  /**
   * The Agent selection column. Every existing Organization gains it as NULL — "every Agent", the default — and
   * nothing else about any row in any table changes. Then an Organization created under this schema, narrowed to two
   * Agents and connected to a registered repository: the next step has to move its credential off the row while
   * leaving the selection where it is.
   */
  @Test(dependsOnMethods = "applies010")
  public void applies020() throws Exception {
    assertEquals(apply("0.2.0"), List.of("0.2.0"));

    try (var connection = connect()) {
      assertEquals(columns(connection, "organizations"), List.of("agents", "github_access_expiration",
          "github_access_token", "github_login", "github_refresh_expiration", "github_refresh_token", "id",
          "insert_instant", "name", "update_instant"));
      assertEquals(query(connection, "SELECT COUNT(*) FROM organizations WHERE agents IS NULL"), "4");
      assertEquals(without(snapshot(connection, "organizations"), "agents"), before.get("organizations"));
      assertUntouched(connection, "members", "brief_sources", "briefs");

      // Out of order on purpose: the codec sorts a selection, and the migration must not have touched it either way.
      execute(connection,
          "INSERT INTO organizations (id, name, github_login, github_access_token, github_access_expiration, "
              + "github_refresh_token, github_refresh_expiration, agents, insert_instant, update_instant) VALUES ('"
              + NARROWED + "', 'Stark', 'tony', 'gho_stark', 1700000021000, 'ghr_stark', 1700000022000, "
              + "'{\"enabled\": [\"CURSOR\", \"CLAUDE\"]}', 1700000020000, 1700000023000)",
          "INSERT INTO members (organization_id, user_id, role, state, invited_by, invited_at, joined_at) VALUES ('"
              + NARROWED + "', '" + OWNER_ID + "', 'OWNER', 'ACTIVE', NULL, NULL, 1700000020000)",
          "INSERT INTO brief_sources (id, organization_id, owner, repository, branch, last_built_commit, "
              + "last_polled_instant, last_status, last_error, insert_instant, update_instant) VALUES ('"
              + NARROWED_SOURCE + "', '" + NARROWED + "', 'Stark', 'Rules', 'develop', 'def456', 1700000024000, "
              + "'UNCHANGED', NULL, 1700000020000, 1700000024000)",
          "INSERT INTO briefs (id, organization_id, version, checksum, document, source_commit, insert_instant) "
              + "VALUES (gen_random_uuid(), '" + NARROWED + "', 1, 'c3', '{\"files\": [2]}', 'def456', 1700000024000)");

      remember(connection);
    }
  }

  /**
   * The typed source. The credential leaves the Organization's row for its source's document, the repository
   * leaves its columns for the same document, and an Organization that held a credential and no source gains one.
   * Read back through the repository and codec the application uses, which is what proves the SQL-built documents
   * are the documents the application expects rather than something that merely looks like them. Then the
   * constraints, and a GitLab source — a kind this step admits — for the next step to keep.
   */
  @Test(dependsOnMethods = "applies020")
  public void applies030() throws Exception {
    assertEquals(apply("0.3.0"), List.of("0.3.0"));

    try (var connection = connect()) {
      assertEquals(columns(connection, "organizations"), List.of("agents", "id", "insert_instant", "name", "update_instant"));
      assertEquals(columns(connection, "brief_sources"), List.of("id", "insert_instant", "last_built_commit",
          "last_error", "last_polled_instant", "last_status", "organization_id", "source", "source_config", "type",
          "update_instant"));
      // The Organizations lost their credential columns and nothing else; the other two tables are as they were.
      assertEquals(snapshot(connection, "organizations"), without(before.get("organizations"), "github_login",
          "github_access_token", "github_access_expiration", "github_refresh_token", "github_refresh_expiration"));
      assertUntouched(connection, "members", "briefs");

      var sources = new BriefSourceRepository(dsl(connection));
      var organizations = new OrganizationRepository(dsl(connection));
      assertEquals(sources.findAll().size(), 4);
      assertEquals(organizations.findAll().size(), 5);

      // Connected and registered: one row, carrying the credential from the Organization's columns and the
      // repository from the source's, as one document. The row's own history and instants are untouched.
      var both = sources.findByOrganizationId(CONNECTED_AND_REGISTERED).orElseThrow();
      assertEquals(both.id(), CONNECTED_AND_REGISTERED_SOURCE);
      assertEquals(both.type(), BriefSourceType.GITHUB);
      assertEquals(both.source(), "Acme/Briefs");
      assertEquals(both.lastBuiltCommit(), "abc123");
      assertEquals(both.lastStatus(), SourceStatus.OK);
      assertEquals(both.insertInstant(), Instant.ofEpochMilli(1_700_000_000_000L));
      assertEquals(both.updateInstant(), Instant.ofEpochMilli(1_700_000_010_000L));
      assertEquals(both.config(), new GitHubConfig(
          new OAuthConnection("octocat", new OAuthTokens("gho_access", Instant.ofEpochMilli(1_700_000_001_000L),
              "ghr_refresh", Instant.ofEpochMilli(1_700_000_002_000L))),
          "Acme", "Briefs", "main"));
      assertTrue(both.connected());
      assertTrue(both.registered());
      // And the lookup the validator runs finds it, case-insensitively, through the new index.
      assertEquals(sources.findBySource(BriefSourceType.GITHUB, "acme/briefs").orElseThrow().id(), both.id());
      // The document carried over exactly: same members, same values, nothing null.
      assertEquals(query(connection, "SELECT source_config::text FROM brief_sources WHERE organization_id = '"
              + CONNECTED_AND_REGISTERED + "'"),
          "{\"type\": \"GITHUB\", \"owner\": \"Acme\", \"branch\": \"main\", \"connection\": "
              + "{\"login\": \"octocat\", \"tokens\": {\"accessToken\": \"gho_access\", \"refreshToken\": \"ghr_refresh\", "
              + "\"accessExpiration\": 1700000001000, \"refreshTokenExpiration\": 1700000002000}}, "
              + "\"repository\": \"Briefs\"}");

      // Connected only: an Organization that had authorized GitHub and never picked a repository had no source row
      // at all. It gets one -- connected, unregistered, exactly what the OAuth callback now creates -- stamped with
      // the Organization's own instants, since its last update was the credential write. A token GitHub issued
      // without expiry: the absent columns are absent members, not nulls, and read back as null.
      var connectedOnly = sources.findByOrganizationId(CONNECTED_ONLY).orElseThrow();
      assertEquals(connectedOnly.type(), BriefSourceType.GITHUB);
      assertNull(connectedOnly.source());
      assertNull(connectedOnly.lastStatus());
      assertEquals(connectedOnly.insertInstant(), Instant.ofEpochMilli(1_700_000_005_000L));
      assertEquals(connectedOnly.updateInstant(), Instant.ofEpochMilli(1_700_000_005_000L));
      assertEquals(connectedOnly.config(), new GitHubConfig(
          new OAuthConnection("hubot", new OAuthTokens("gho_forever", null, null, null)), null, null, null));
      assertTrue(connectedOnly.connected());
      assertFalse(connectedOnly.registered());

      // Registered only: the repository survives with no connection, which is the disconnected state the admin UI
      // warns about and the poller reports as NOT_CONNECTED.
      var registeredOnly = sources.findByOrganizationId(REGISTERED_ONLY).orElseThrow();
      assertEquals(registeredOnly.id(), REGISTERED_ONLY_SOURCE);
      assertEquals(registeredOnly.source(), "Globex/Rules");
      assertEquals(registeredOnly.lastStatus(), SourceStatus.NOT_CONNECTED);
      assertEquals(registeredOnly.lastError(), "lapsed");
      assertEquals(registeredOnly.config(), new GitHubConfig(null, "Globex", "Rules", "develop"));
      assertFalse(registeredOnly.connected());
      assertTrue(registeredOnly.registered());

      // Neither: no source. Nothing about the Organization itself changed but the columns that left.
      assertTrue(sources.findByOrganizationId(NEITHER).isEmpty());
      assertEquals(organizations.findById(NEITHER).orElseThrow(),
          new Organization(NEITHER, "Umbrella", null, Instant.ofEpochMilli(1_700_000_008_000L),
              Instant.ofEpochMilli(1_700_000_009_000L)));

      // Narrowed, created under 0.2.0: the credential moved like the others', and the selection stayed -- as the
      // codec reads it, canonically sorted, and as the column holds it, in the order it was written.
      var narrowed = sources.findByOrganizationId(NARROWED).orElseThrow();
      assertEquals(narrowed.id(), NARROWED_SOURCE);
      assertEquals(narrowed.source(), "Stark/Rules");
      assertEquals(narrowed.lastBuiltCommit(), "def456");
      assertEquals(narrowed.lastStatus(), SourceStatus.UNCHANGED);
      assertEquals(narrowed.config(), new GitHubConfig(
          new OAuthConnection("tony", new OAuthTokens("gho_stark", Instant.ofEpochMilli(1_700_000_021_000L),
              "ghr_stark", Instant.ofEpochMilli(1_700_000_022_000L))),
          "Stark", "Rules", "develop"));
      assertEquals(organizations.findById(NARROWED).orElseThrow().agents(), new Agents(List.of(Agent.CLAUDE, Agent.CURSOR)));
      assertEquals(query(connection, "SELECT agents::text FROM organizations WHERE id = '" + NARROWED + "'"),
          "{\"enabled\": [\"CURSOR\", \"CLAUDE\"]}");

      // The constraints as this step leaves them: the set of kinds is GitHub and GitLab and nothing else yet, the
      // column and the document must name the same kind, the identity is unique per kind and case-insensitively,
      // and the document is required.
      assertRejected(connection, "INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
          + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'BITBUCKET', NULL, "
          + "'{\"type\": \"BITBUCKET\"}', 0, 0)", "brief_sources_ck_type");
      assertRejected(connection, "INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
          + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITHUB', NULL, "
          + "'{\"type\": \"OTHER\"}', 0, 0)", "brief_sources_ck_source_config_type");
      assertRejected(connection, "INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
          + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITHUB', 'ACME/BRIEFS', "
          + "'{\"type\": \"GITHUB\"}', 0, 0)", "brief_sources_uk_source");
      assertRejected(connection, "INSERT INTO brief_sources (id, organization_id, type, source_config, "
          + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITHUB', NULL, 0, 0)",
          "null value");

      // A second unregistered source is not a collision: NULL identities are distinct.
      execute(connection,
          "INSERT INTO brief_sources (id, organization_id, type, source, source_config, insert_instant, "
              + "update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITHUB', NULL, '{\"type\": \"GITHUB\"}', "
              + "0, 0)",
          "DELETE FROM brief_sources WHERE organization_id = '" + NEITHER + "'");

      // The kind this step admits, for the next step to keep: a GitLab source on the Organization that had none.
      execute(connection, "INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
          + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITLAB', 'acme/briefs', "
          + "'{\"type\": \"GITLAB\", \"baseURL\": \"https://gitlab.com\", \"project\": \"acme/briefs\", "
          + "\"branch\": \"main\"}', 1700000030000, 1700000030000)");
      var gitLab = sources.findByOrganizationId(NEITHER).orElseThrow();
      assertEquals(gitLab.type(), BriefSourceType.GITLAB);
      assertEquals(gitLab.config(), new GitLabConfig(null, "https://gitlab.com", "acme/briefs", "main"));
      assertTrue(gitLab.registered());
      assertFalse(gitLab.connected());

      remember(connection);
    }
  }

  /**
   * Bitbucket admitted to the set of kinds. No row changes — the step only widens a CHECK, and re-checking every
   * existing row against it is what applying it did — so every table is exactly as it was. Then the widening
   * itself: a Bitbucket source goes in and reads back through the codec, under the same identity as the GitLab
   * source because the identity is unique per kind, and a kind nobody added is still refused.
   */
  @Test(dependsOnMethods = "applies030")
  public void applies040() throws Exception {
    assertEquals(apply("0.4.0"), List.of("0.4.0"));

    try (var connection = connect()) {
      assertUntouched(connection, "organizations", "members", "brief_sources", "briefs");

      execute(connection,
          "INSERT INTO organizations (id, name, insert_instant, update_instant) VALUES ('" + LATEST
              + "', 'Wayne', 1700000040000, 1700000040000)",
          "INSERT INTO brief_sources (id, organization_id, type, source, source_config, insert_instant, "
              + "update_instant) VALUES (gen_random_uuid(), '" + LATEST + "', 'BITBUCKET', 'acme/briefs', "
              + "'{\"type\": \"BITBUCKET\", \"repository\": \"acme/briefs\", \"branch\": \"main\"}', 1700000040000, "
              + "1700000040000)");

      var sources = new BriefSourceRepository(dsl(connection));
      var bitbucket = sources.findByOrganizationId(LATEST).orElseThrow();
      assertEquals(bitbucket.type(), BriefSourceType.BITBUCKET);
      assertEquals(bitbucket.config(), new BitbucketConfig(null, "acme/briefs", "main"));
      assertEquals(bitbucket.config().url(), "https://bitbucket.org/acme/briefs");
      assertTrue(bitbucket.registered());
      assertFalse(bitbucket.connected());
      assertEquals(sources.findBySource(BriefSourceType.BITBUCKET, "ACME/BRIEFS").orElseThrow().id(), bitbucket.id());
      assertEquals(sources.findBySource(BriefSourceType.GITLAB, "acme/briefs").orElseThrow().organizationId(), NEITHER);
      assertEquals(sources.findAll().size(), 6);

      assertRejected(connection, "INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
          + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + LATEST + "', 'SUBVERSION', NULL, "
          + "'{\"type\": \"SUBVERSION\"}', 0, 0)", "brief_sources_ck_type");
    }
  }

  /**
   * The walk covered every shipped migration: the shipped directory itself has nothing left to apply, and the
   * application opens the database with nothing to do.
   */
  @Test(dependsOnMethods = "applies040")
  public void nothingIsLeftToApply() throws Exception {
    try (var connection = connect()) {
      assertEquals(new Migrator(connection, MIGRATIONS).migrate(), List.of());
      assertEquals(rows(connection, "SELECT version FROM versions ORDER BY version"),
          List.of("0.1.0", "0.2.0", "0.3.0", "0.4.0"));
    }

    new Database(config).close();
    try (var connection = connect()) {
      assertEquals(query(connection, "SELECT COUNT(*) FROM versions"), "4");
    }
  }

  private static void assertRejected(Connection connection, String sql, String reason) {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
      fail("Expected [" + sql + "] to be rejected");
    } catch (SQLException e) {
      assertTrue(e.getMessage().contains(reason), e.getMessage());
    }
  }

  private static List<String> columns(Connection connection, String table) throws SQLException {
    return rows(connection, "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table
        + "' ORDER BY column_name");
  }

  private static DSLContext dsl(Connection connection) {
    return DSL.using(connection, SQLDialect.POSTGRES);
  }

  private static void execute(Connection connection, String... statements) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }

  /**
   * @return The first column of the first row, as text.
   */
  private static String query(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
      assertTrue(resultSet.next(), sql);
      return resultSet.getString(1);
    }
  }

  /**
   * @return The first column of every row, as text.
   */
  private static List<String> rows(Connection connection, String sql) throws SQLException {
    var values = new ArrayList<String>();
    try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        values.add(resultSet.getString(1));
      }
    }
    return values;
  }

  /**
   * Every row of a table, every column as text, in primary-key order — so two snapshots compare column for column
   * whatever the types, and a migration that changed one value in one row shows up as that value.
   */
  private static List<Map<String, String>> snapshot(Connection connection, String table) throws SQLException {
    var order = switch (table) {
      case "members" -> "organization_id, user_id";
      case "briefs" -> "organization_id, version";
      default -> "id";
    };
    var rows = new ArrayList<Map<String, String>>();
    try (var statement = connection.createStatement();
         var resultSet = statement.executeQuery("SELECT * FROM " + table + " ORDER BY " + order)) {
      var metaData = resultSet.getMetaData();
      while (resultSet.next()) {
        var row = new HashMap<String, String>();
        for (var i = 1; i <= metaData.getColumnCount(); i++) {
          row.put(metaData.getColumnName(i), resultSet.getString(i));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  private static List<String> tables(Connection connection) throws SQLException {
    return rows(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
        + "ORDER BY table_name");
  }

  /**
   * @return The rows with the named columns dropped, for comparing a snapshot across a step that added or removed
   *     them.
   */
  private static List<Map<String, String>> without(List<Map<String, String>> rows, String... columns) {
    return rows.stream().map(row -> {
      Map<String, String> copy = new HashMap<>(row);
      for (var column : columns) {
        copy.remove(column);
      }
      return copy;
    }).toList();
  }

  /**
   * Copies one shipped migration into the steps directory and applies whatever is pending there — which is that
   * one file, since every earlier one is already applied.
   *
   * @return The versions applied, as the Migrator reports them.
   */
  private List<String> apply(String version) throws Exception {
    Files.copy(MIGRATIONS.resolve(version + ".sql"), steps.resolve(version + ".sql"));
    try (var connection = connect()) {
      return new Migrator(connection, steps).migrate().stream().map(Object::toString).toList();
    }
  }

  /**
   * Asserts that tables the step under test does not touch are exactly as the previous step left them.
   */
  private void assertUntouched(Connection connection, String... tables) throws SQLException {
    for (var table : tables) {
      assertEquals(snapshot(connection, table), before.get(table), table);
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(config.get("db.url"), config.get("db.username"), config.get("db.password"));
  }

  /**
   * Snapshots every table at the end of a step, for the next step to compare against.
   */
  private void remember(Connection connection) throws SQLException {
    for (var table : List.of("organizations", "members", "brief_sources", "briefs")) {
      before.put(table, snapshot(connection, table));
    }
  }
}
