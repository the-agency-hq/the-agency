/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module dev.theagencyhq.agency;
import module java.base;
import module java.sql;
import module org.lattejava.database;
import module org.testng;

import java.nio.file.Files;
import java.sql.Connection;
import org.lattejava.web.Configuration;

import static org.testng.Assert.*;

/**
 * The {@code 0.3.0} migration, which moves the GitHub credential off {@code organizations} and the repository off
 * its own columns into the typed {@code brief_sources} row — against data, not an empty schema.
 *
 * <p>The suite's own database is always at the newest version, so this builds a scratch database from the
 * migrations that shipped before {@code 0.3.0}, seeds it with every shape a production row could be in, applies
 * {@code 0.3.0}, and reads the result back through {@code BriefSourceRepository} over a {@code Database} of its own — the same codec the poller reads with,
 * which is what proves the SQL-built documents are the documents the application expects rather than something
 * that merely looks like them. Constructing that {@code Database} also carries the scratch database on to the newest
 * migration, as the application would, so the schema assertions describe the current schema rather than 0.3.0's.
 */
@Test
public class MigrationTest {
  private static final String DATABASE = "the_agency_migration_test";
  private static final Path MIGRATIONS = Path.of("src/main/resources/db");
  private static final UUID CONNECTED_AND_REGISTERED = UUID.randomUUID();
  private static final UUID CONNECTED_ONLY = UUID.randomUUID();
  private static final UUID NEITHER = UUID.randomUUID();
  private static final UUID REGISTERED_ONLY = UUID.randomUUID();
  private static final UUID REGISTERED_ONLY_SOURCE = UUID.randomUUID();
  private static final UUID CONNECTED_AND_REGISTERED_SOURCE = UUID.randomUUID();
  private Configuration config;
  private Path scratch;

  @AfterClass
  public void afterClass() throws Exception {
    if (scratch != null) {
      try (var files = Files.walk(scratch)) {
        files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  /**
   * Builds the pre-{@code 0.3.0} schema in a scratch database, seeds it, and migrates it. One database for the
   * class, so every assertion below reads the same migrated rows.
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

    // A fresh database each run: the point is to migrate from 0.2.0, which a database left at 0.3.0 cannot do.
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

    // The migrations that shipped before 0.3.0, applied from a directory holding only them.
    var before = scratch.resolve("before");
    Files.createDirectories(before);
    Files.copy(MIGRATIONS.resolve("0.1.0.sql"), before.resolve("0.1.0.sql"));
    Files.copy(MIGRATIONS.resolve("0.2.0.sql"), before.resolve("0.2.0.sql"));
    try (var connection = connect()) {
      assertEquals(new Migrator(connection, before).migrate().size(), 2);
      seed(connection);
    }

    // Then 0.3.0 alone, on top -- from the same directory the application ships, so this is the file itself.
    Files.copy(MIGRATIONS.resolve("0.3.0.sql"), before.resolve("0.3.0.sql"));
    try (var connection = connect()) {
      var applied = new Migrator(connection, before).migrate();
      assertEquals(applied.size(), 1);
      assertEquals(applied.getFirst().toString(), "0.3.0");
    }
  }

  /**
   * An Organization that had authorized GitHub and picked a repository: one row, carrying the credential from the
   * Organization's columns and the repository from the source's, as one document.
   */
  @Test
  public void aConnectedAndRegisteredSourceCarriesBoth() throws Exception {
    var database = new Database(config);
    try {
      var sources = new BriefSourceRepository(database.dsl());
      var source = sources.findByOrganizationId(CONNECTED_AND_REGISTERED).orElseThrow();
      assertEquals(source.id(), CONNECTED_AND_REGISTERED_SOURCE);
      assertEquals(source.type(), BriefSourceType.GITHUB);
      assertEquals(source.source(), "Acme/Briefs");
      assertEquals(source.lastBuiltCommit(), "abc123");
      assertEquals(source.lastStatus(), SourceStatus.OK);
      assertEquals(source.insertInstant(), Instant.ofEpochMilli(1_700_000_000_000L));

      var expected = new GitHubConfig(
          new OAuthConnection("octocat", new OAuthTokens("gho_access", Instant.ofEpochMilli(1_700_000_001_000L),
              "ghr_refresh", Instant.ofEpochMilli(1_700_000_002_000L))),
          "Acme", "Briefs", "main");
      assertEquals(source.config(), expected);
      assertTrue(source.connected());
      assertTrue(source.registered());

      // And the lookup the validator runs still finds it, case-insensitively, through the new index.
      assertEquals(sources.findBySource(BriefSourceType.GITHUB, "acme/briefs").orElseThrow().id(), source.id());
    } finally {
      database.close();
    }
  }

  /**
   * An Organization that had authorized GitHub and never picked a repository had no source row at all. It gets
   * one: connected, unregistered, exactly what the OAuth callback now creates.
   */
  @Test
  public void aCredentialWithoutASourceBecomesAnUnregisteredSource() throws Exception {
    var database = new Database(config);
    try {
      var sources = new BriefSourceRepository(database.dsl());
      var source = sources.findByOrganizationId(CONNECTED_ONLY).orElseThrow();
      assertEquals(source.type(), BriefSourceType.GITHUB);
      assertNull(source.source());
      assertNull(source.lastStatus());
      // The Organization's own instants: its last update was the credential write.
      assertEquals(source.insertInstant(), Instant.ofEpochMilli(1_700_000_005_000L));
      assertEquals(source.updateInstant(), Instant.ofEpochMilli(1_700_000_005_000L));

      // A token GitHub issued without expiry: the absent columns are absent members, not nulls, and read back as
      // null.
      var expected = new GitHubConfig(
          new OAuthConnection("hubot", new OAuthTokens("gho_forever", null, null, null)), null, null, null);
      assertEquals(source.config(), expected);
      assertTrue(source.connected());
      assertFalse(source.registered());
    } finally {
      database.close();
    }
  }

  /**
   * A source whose Organization's credential had lapsed: the repository survives with no connection, which is the
   * disconnected state the admin UI warns about and the poller reports as NOT_CONNECTED.
   */
  @Test
  public void aSourceWithoutACredentialIsDisconnected() throws Exception {
    var database = new Database(config);
    try {
      var sources = new BriefSourceRepository(database.dsl());
      var source = sources.findByOrganizationId(REGISTERED_ONLY).orElseThrow();
      assertEquals(source.id(), REGISTERED_ONLY_SOURCE);
      assertEquals(source.source(), "Globex/Rules");
      assertEquals(source.lastStatus(), SourceStatus.NOT_CONNECTED);
      assertEquals(source.lastError(), "lapsed");
      assertEquals(source.config(), new GitHubConfig(null, "Globex", "Rules", "develop"));
      assertFalse(source.connected());
      assertTrue(source.registered());
    } finally {
      database.close();
    }
  }

  @Test
  public void anOrganizationWithNeitherGetsNoSource() throws Exception {
    var database = new Database(config);
    try {
      var sources = new BriefSourceRepository(database.dsl());
      assertTrue(sources.findByOrganizationId(NEITHER).isEmpty());
      assertEquals(sources.findAll().size(), 3);
      // Nothing about the Organizations themselves changed but the columns that left.
      assertEquals(new OrganizationRepository(database.dsl()).findAll().size(), 4);
    } finally {
      database.close();
    }
  }

  /**
   * The schema itself: the old columns are gone from both tables, the new ones are constrained, and the unique
   * index does the job the old one did — one repository per Organization, case-insensitively — while letting any
   * number of unregistered sources coexist.
   */
  @Test
  public void theSchemaIsConstrained() throws Exception {
    // Whatever order the methods ran in, the scratch database is at the newest migration from here on.
    new Database(config).close();

    try (var connection = connect()) {
      assertEquals(columns(connection, "organizations"),
          List.of("agents", "id", "insert_instant", "name", "update_instant"));
      assertEquals(columns(connection, "brief_sources"),
          List.of("id", "insert_instant", "last_built_commit", "last_error", "last_polled_instant", "last_status",
              "organization_id", "source", "source_config", "type", "update_instant"));

      // The document carried over exactly: same members, same values, nothing null.
      var stored = query(connection, "SELECT source_config::text FROM brief_sources WHERE organization_id = '"
          + CONNECTED_AND_REGISTERED + "'");
      assertEquals(stored, "{\"type\": \"GITHUB\", \"owner\": \"Acme\", \"branch\": \"main\", \"connection\": "
          + "{\"login\": \"octocat\", \"tokens\": {\"accessToken\": \"gho_access\", \"refreshToken\": \"ghr_refresh\", "
          + "\"accessExpiration\": 1700000001000, \"refreshTokenExpiration\": 1700000002000}}, "
          + "\"repository\": \"Briefs\"}");

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
      try (var statement = connection.createStatement()) {
        statement.execute("INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
            + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITHUB', NULL, "
            + "'{\"type\": \"GITHUB\"}', 0, 0)");
        statement.execute("DELETE FROM brief_sources WHERE organization_id = '" + NEITHER + "'");
      }

      // GITLAB, which 0.4.0 admitted to the CHECK, goes in and reads back through the codec as a GitLab source.
      try (var statement = connection.createStatement()) {
        statement.execute("INSERT INTO brief_sources (id, organization_id, type, source, source_config, "
            + "insert_instant, update_instant) VALUES (gen_random_uuid(), '" + NEITHER + "', 'GITLAB', 'acme/briefs', "
            + "'{\"type\": \"GITLAB\", \"baseURL\": \"https://gitlab.com\", \"project\": \"acme/briefs\", "
            + "\"branch\": \"main\"}', 0, 0)");
      }
      var database = new Database(config);
      try {
        var source = new BriefSourceRepository(database.dsl()).findByOrganizationId(NEITHER).orElseThrow();
        assertEquals(source.type(), BriefSourceType.GITLAB);
        assertEquals(source.config(), new GitLabConfig(null, "https://gitlab.com", "acme/briefs", "main"));
        assertTrue(source.registered());
        assertFalse(source.connected());
      } finally {
        database.close();
      }
      try (var statement = connection.createStatement()) {
        statement.execute("DELETE FROM brief_sources WHERE organization_id = '" + NEITHER + "'");
      }
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
    var columns = new ArrayList<String>();
    try (var statement = connection.prepareStatement(
        "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY column_name")) {
      statement.setString(1, table);
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          columns.add(resultSet.getString(1));
        }
      }
    }
    return columns;
  }

  private static String query(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
      assertTrue(resultSet.next());
      return resultSet.getString(1);
    }
  }

  /**
   * Every shape a production row could be in on the day the migration runs: connected and registered, connected
   * only, registered only (the credential lapsed and was cleared), and neither.
   */
  private static void seed(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("INSERT INTO organizations (id, name, github_login, github_access_token, "
          + "github_access_expiration, github_refresh_token, github_refresh_expiration, insert_instant, "
          + "update_instant) VALUES ('" + CONNECTED_AND_REGISTERED + "', 'Acme', 'octocat', 'gho_access', "
          + "1700000001000, 'ghr_refresh', 1700000002000, 1700000000000, 1700000003000)");
      statement.execute("INSERT INTO organizations (id, name, github_login, github_access_token, insert_instant, "
          + "update_instant) VALUES ('" + CONNECTED_ONLY + "', 'Initech', 'hubot', 'gho_forever', 1700000004000, "
          + "1700000005000)");
      statement.execute("INSERT INTO organizations (id, name, insert_instant, update_instant) VALUES ('"
          + REGISTERED_ONLY + "', 'Globex', 1700000006000, 1700000007000)");
      statement.execute("INSERT INTO organizations (id, name, insert_instant, update_instant) VALUES ('"
          + NEITHER + "', 'Umbrella', 1700000008000, 1700000009000)");

      statement.execute("INSERT INTO brief_sources (id, organization_id, owner, repository, branch, "
          + "last_built_commit, last_polled_instant, last_status, last_error, insert_instant, update_instant) "
          + "VALUES ('" + CONNECTED_AND_REGISTERED_SOURCE + "', '" + CONNECTED_AND_REGISTERED + "', 'Acme', "
          + "'Briefs', 'main', 'abc123', 1700000010000, 'OK', NULL, 1700000000000, 1700000010000)");
      statement.execute("INSERT INTO brief_sources (id, organization_id, owner, repository, branch, "
          + "last_built_commit, last_polled_instant, last_status, last_error, insert_instant, update_instant) "
          + "VALUES ('" + REGISTERED_ONLY_SOURCE + "', '" + REGISTERED_ONLY + "', 'Globex', 'Rules', 'develop', "
          + "NULL, 1700000011000, 'NOT_CONNECTED', 'lapsed', 1700000006000, 1700000011000)");
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(config.get("db.url"), config.get("db.username"), config.get("db.password"));
  }
}
