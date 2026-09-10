/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.db;

import module com.zaxxer.hikari;
import module java.base;
import module org.jooq;
import module org.lattejava.database;

import java.sql.Connection;
import org.lattejava.web.Configuration;

/**
 * The database: the HikariCP pool, the migrations applied to it, and the one jOOQ {@link DSLContext} everything
 * else queries through. Nothing else touches connections or the persistence technology directly.
 *
 * <p>The application holds exactly one, opened by {@link #initialize} and reached through {@link #instance()} —
 * the repositories are built on its {@link #dsl()}, and {@link #shutdown()} closes it. The constructor is public
 * for one reason: a test that migrates a scratch database, or proves what a failed migration leaves behind, needs
 * a database of its own beside the application's.
 *
 * <p>Transactions are thread-bound: inside {@link #transaction} or {@link #transactionResult}, every statement any
 * repository runs on the calling thread joins the transaction, so a service composes writes across repositories
 * into one unit without threading a transactional context through every repository method.
 */
public final class Database {
  private static final System.Logger logger = System.getLogger(Database.class.getName());
  private static Database instance;
  private final HikariDataSource dataSource;
  private final DSLContext dsl;

  /**
   * Opens the pool the {@code db.*} settings describe and applies any pending classpath migrations
   * ({@code db/*.sql}) to it.
   *
   * @param config The configuration.
   * @throws IllegalStateException if the migrations cannot be applied. The pool is closed before this is thrown, or
   *     it would leak for the life of the JVM.
   */
  public Database(Configuration config) {
    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(config.get("db.url"));
    hikariConfig.setUsername(config.get("db.username"));
    hikariConfig.setPassword(config.get("db.password"));
    hikariConfig.setPoolName("the-agency");
    this.dataSource = new HikariDataSource(hikariConfig);

    try (Connection connection = dataSource.getConnection()) {
      var applied = new Migrator(connection, "db").migrate();
      if (!applied.isEmpty()) {
        logger.log(System.Logger.Level.INFO, "Applied database migrations [{0}]", applied);
      }
    } catch (MigrationException | SQLException e) {
      // The pool never escapes this constructor on the failure path (it throws below), so close it here or it
      // leaks for the life of the JVM. Don't let a failure while closing mask the original migration failure.
      try {
        dataSource.close();
      } catch (RuntimeException closeException) {
        e.addSuppressed(closeException);
      }
      throw new IllegalStateException("Unable to migrate the database [" + config.get("db.url") + "]", e);
    }

    // Thread-bound transactions: see the class comment.
    var connections = new DataSourceConnectionProvider(dataSource);
    this.dsl = DSL.using(new DefaultConfiguration().set(connections)
                                                   .set(SQLDialect.POSTGRES)
                                                   .set(new ThreadLocalTransactionProvider(connections)));
  }

  /**
   * Opens the application's database. A database left open by an earlier call is closed first, so the JVM never
   * holds two pools for the application.
   *
   * @param config The configuration.
   * @return The database, which {@link #instance()} returns from now on.
   */
  public static Database initialize(Configuration config) {
    if (instance != null) {
      throw new IllegalStateException("Coding error. This must be initialized at startup.");
    }

    instance = new Database(config);
    return instance;
  }

  /**
   * @return The application's database.
   * @throws IllegalStateException if {@link #initialize} has not been called, or {@link #shutdown()} has been.
   */
  public static Database instance() {
    if (instance == null) {
      throw new IllegalStateException("The database has not been initialized");
    }

    return instance;
  }

  /**
   * Closes the application's database. Idempotent.
   */
  public static void shutdown() {
    if (instance != null) {
      instance.close();
    }
  }

  /**
   * Closes the pool. Closing the application's own database also un-registers it, so {@link #instance()} can never
   * hand out a closed pool.
   */
  public void close() {
    dataSource.close();
    if (instance == this) {
      instance = null;
    }
  }

  public DSLContext dsl() {
    return dsl;
  }

  /**
   * Runs work inside one transaction bound to the calling thread: every statement any repository executes on this
   * thread until the work returns is part of it, committed together or rolled back together.
   *
   * @param work The work.
   */
  public void transaction(Runnable work) {
    dsl.transaction(work::run);
  }

  /**
   * @param work The work.
   * @param <T>  The result type.
   * @return What the work returned, once the transaction has committed.
   * @see #transaction(Runnable)
   */
  public <T> T transactionResult(Supplier<T> work) {
    return dsl.transactionResult(work::get);
  }
}
