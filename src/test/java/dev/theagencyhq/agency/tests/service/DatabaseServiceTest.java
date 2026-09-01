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

import java.sql.Connection;
import java.time.Duration;
import org.lattejava.web.Configuration;

import static dev.theagencyhq.agency.db.jooq.Tables.*;
import static org.testng.Assert.*;

@SuppressWarnings("BusyWait")
@Test
public class DatabaseServiceTest {
  private static void assertNoLeakedPoolThreads(Set<String> threadsBefore) throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    Set<String> leaked;
    do {
      leaked = poolThreads();
      leaked.removeAll(threadsBefore);
      if (leaked.isEmpty()) {
        return;
      }
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    fail("HikariCP pool threads leaked after a migration failure [" + leaked + "]");
  }

  // Corrupts the checksum recorded for the 0.1.0 migration so the *next* Migrator run fails with a
  // ChecksumException, and returns the original checksum so the caller can restore it. This reproduces a
  // migration failure against a database that accepts connections fine -- unlike an unreachable db.url, which
  // fails HikariCP's own fail-fast pool-initialization check (a PoolInitializationException thrown by
  // `new HikariDataSource(...)` itself) before DatabaseService's migration try/catch is ever reached.
  private static String corruptChecksum(Configuration config) throws SQLException {
    try (Connection connection = rawConnection(config)) {
      try {
        new Migrator(connection, "db").migrate(); // Idempotent: ensures the 0.1.0 row already exists.
      } catch (MigrationException e) {
        throw new IllegalStateException("Test setup failed to migrate [" + config.get("db.url") + "]", e);
      }

      String original;
      try (PreparedStatement select = connection.prepareStatement("SELECT checksum FROM versions WHERE version = ?")) {
        select.setString(1, "0.1.0");
        try (ResultSet resultSet = select.executeQuery()) {
          resultSet.next();
          original = resultSet.getString(1);
        }
      }

      var updateSQL = "UPDATE versions SET checksum = ? WHERE version = ?";
      try (PreparedStatement update = connection.prepareStatement(updateSQL)) {
        update.setString(1, "0".repeat(64));
        update.setString(2, "0.1.0");
        update.executeUpdate();
      }

      return original;
    }
  }

  private static Set<String> poolThreads() {
    // "the-agency" is the pool name DatabaseService assigns to every HikariDataSource it creates.
    return Thread.getAllStackTraces()
                 .keySet()
                 .stream()
                 .map(Thread::getName)
                 .filter(name -> name.contains("the-agency"))
                 .collect(Collectors.toSet());
  }

  private static Connection rawConnection(Configuration config) throws SQLException {
    return DriverManager.getConnection(config.get("db.url"), config.get("db.username"), config.get("db.password"));
  }

  private static void restoreChecksum(Configuration config, String originalChecksum) throws SQLException {
    try (Connection connection = rawConnection(config);
         PreparedStatement update = connection.prepareStatement("UPDATE versions SET checksum = ? WHERE version = ?")) {
      update.setString(1, originalChecksum);
      update.setString(2, "0.1.0");
      update.executeUpdate();
    }
  }

  @Test
  public void closesPoolOnMigrationFailure() throws Exception {
    var config = new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties")
    );
    var url = config.get("db.url");

    var originalChecksum = corruptChecksum(config);
    try {
      var threadsBefore = poolThreads();

      var exception = expectThrows(IllegalStateException.class, () -> new DatabaseService(config));
      assertEquals(exception.getMessage(), "Unable to migrate the database [" + url + "]");
      assertTrue(exception.getCause() instanceof MigrationException);

      // The pool created during the failed construction must not survive it, or it leaks for the life of the
      // JVM since the constructor never returns and no caller ever gets a reference to close it.
      assertNoLeakedPoolThreads(threadsBefore);
    } finally {
      restoreChecksum(config, originalChecksum);
    }
  }

  @Test
  public void migratesAndQueries() {
    var config = new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties")
    );

    var service = new DatabaseService(config);
    try {
      assertNotNull(service.dsl());

      // The migration ran, so the table exists and is queryable. Deliberately not asserting the count is zero: it
      // used to be the suite's leak detector, back when every class cleaned up after itself and a forgotten delete
      // surfaced here. BaseTest empties the database before every method now, so isolation is structural and this
      // assertion would only be testing which class happened to run last.
      assertNotNull(service.dsl().selectCount().from(ORGANIZATIONS).fetchOne(0, int.class));
    } finally {
      service.close();
    }
  }

  /**
   * The poll cycle reads {@code listSources()} once and then works through the result, so an Organization deleted
   * partway through leaves it recording a status against a {@code brief_sources} row the cascade has already removed.
   * An UPDATE matching zero rows is a normal, successful no-op in SQL rather than an error, and the poller depends on
   * that: if this threw, one deleted Organization would take down the rest of the cycle with it.
   */
  @Test
  public void updateSourceStatusAgainstADeletedOrganizationIsANoOp() {
    var config = new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties")
    );

    var service = new DatabaseService(config);
    try {
      var now = Instant.now();
      service.updateSourceStatus(UUID.randomUUID(), "abc123", now, SourceStatus.BUILD_FAILED, "gone", now);
    } finally {
      service.close();
    }
  }
}
