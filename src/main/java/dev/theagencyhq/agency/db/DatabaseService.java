/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.db;

import module java.base;
import module org.lattejava.database;

import com.zaxxer.hikari.*;
import dev.theagencyhq.agency.error.*;
import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.internal.*;
import org.jooq.*;
import org.jooq.exception.*;
import org.jooq.impl.*;
import org.lattejava.web.Configuration;
import org.postgresql.util.*;

import static dev.theagencyhq.agency.db.jooq.Tables.*;

/**
 * PostgreSQL-backed data access, implemented with jOOQ over a HikariCP connection pool. This service owns the
 * persistence setup entirely — it builds the data source and jOOQ context from the {@code db.*} configuration and
 * applies any pending classpath migrations ({@code db/*.sql}) at construction time — so no other class touches
 * connections or the persistence technology directly.
 */
public class DatabaseService {
  private static final System.Logger logger = System.getLogger(DatabaseService.class.getName());
  private final HikariDataSource dataSource;
  private final DSLContext dsl;

  public DatabaseService(Configuration config) {
    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(config.get("db.url"));
    hikariConfig.setUsername(config.get("db.username"));
    hikariConfig.setPassword(config.get("db.password"));
    hikariConfig.setPoolName("the-agency");
    this.dataSource = new HikariDataSource(hikariConfig);

    try (Connection connection = dataSource.getConnection()) {
      var applied = new Migrator(connection, "db").migrate();
      if (!applied.isEmpty()) {
        logger.log(System.Logger.Level.INFO, "Applied database migrations [" + applied + "]");
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

    this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  // The single place each table's insert column list is written. Both the standalone public inserts and the
  // transactional createOrganizationWithSource route through these, so adding a column is one edit rather than
  // three -- of which the third was previously easy to miss entirely, and would have failed only at runtime.
  private static void insertOrganization(DSLContext context, Organization organization) {
    context.insertInto(ORGANIZATIONS)
           .set(ORGANIZATIONS.ID, organization.id())
           .set(ORGANIZATIONS.NAME, organization.name())
           .set(ORGANIZATIONS.INSERT_INSTANT, organization.insertInstant())
           .set(ORGANIZATIONS.UPDATE_INSTANT, organization.updateInstant())
           .execute();
  }

  private static void insertSource(DSLContext context, BriefSource source) {
    context.insertInto(BRIEF_SOURCES)
           .set(BRIEF_SOURCES.ID, source.id())
           .set(BRIEF_SOURCES.ORGANIZATION_ID, source.organizationId())
           .set(BRIEF_SOURCES.PATH, source.path())
           .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, source.lastBuiltCommit())
           .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, source.lastPolledInstant())
           .set(BRIEF_SOURCES.LAST_STATUS, source.lastStatus())
           .set(BRIEF_SOURCES.LAST_ERROR, source.lastError())
           .set(BRIEF_SOURCES.LAST_PULL_ERROR, source.lastPullError())
           .set(BRIEF_SOURCES.INSERT_INSTANT, source.insertInstant())
           .set(BRIEF_SOURCES.UPDATE_INSTANT, source.updateInstant())
           .execute();
  }

  // The stored document holds only what is content-addressed -- the Organization, the files and the checksum over
  // them. Version and provenance are columns, put back here, so each is stored in exactly one place. Parsing here
  // rather than at each call site is what lets every caller work in Briefs and never in JSON text.
  private static Brief toBrief(org.jooq.Record record) {
    var stored = BriefJSON.fromJSON(record.get(BRIEFS.DOCUMENT));
    return new Brief(stored.checksum(), stored.organization(), record.get(BRIEFS.VERSION), stored.files(),
        record.get(BRIEFS.SOURCE_COMMIT), record.get(BRIEFS.INSERT_INSTANT));
  }

  private static Organization toOrganization(org.jooq.Record record) {
    return new Organization(
        record.get(ORGANIZATIONS.ID),
        record.get(ORGANIZATIONS.NAME),
        record.get(ORGANIZATIONS.INSERT_INSTANT),
        record.get(ORGANIZATIONS.UPDATE_INSTANT));
  }

  private static BriefSource toSource(org.jooq.Record record) {
    return new BriefSource(
        record.get(BRIEF_SOURCES.ID),
        record.get(BRIEF_SOURCES.ORGANIZATION_ID),
        record.get(BRIEF_SOURCES.PATH),
        record.get(BRIEF_SOURCES.LAST_BUILT_COMMIT),
        record.get(BRIEF_SOURCES.LAST_POLLED_INSTANT),
        record.get(BRIEF_SOURCES.LAST_STATUS),
        record.get(BRIEF_SOURCES.LAST_ERROR),
        record.get(BRIEF_SOURCES.LAST_PULL_ERROR),
        record.get(BRIEF_SOURCES.INSERT_INSTANT),
        record.get(BRIEF_SOURCES.UPDATE_INSTANT));
  }

  // Translates a unique-constraint violation raised by createOrganizationWithSource's transaction into the same
  // ValidationException shape OrganizationValidator's up-front check throws, keyed off Postgres's own reported
  // constraint name (verified directly against this schema) rather than parsing the exception's free-text message,
  // which is not a stable contract across Postgres versions. Any other DataAccessException is not ours to
  // interpret, so it is returned unchanged for the caller to rethrow as-is.
  private static RuntimeException translateUniqueViolation(DataAccessException e, Organization organization,
                                                           BriefSource source) {
    var postgres = e.getCause(PSQLException.class);
    var constraint = postgres == null || postgres.getServerErrorMessage() == null ? null
        : postgres.getServerErrorMessage().getConstraint();

    if ("brief_sources_path_key".equals(constraint)) {
      return new ValidationException(
          List.of("The path [" + source.path() + "] is already registered to another Organization."));
    }
    if ("organizations_uk_name".equals(constraint)) {
      return new ValidationException(List.of("The name [" + organization.name() + "] is already registered."));
    }

    return e;
  }

  public void close() {
    dataSource.close();
  }

  /**
   * Inserts a new Organization and its single {@link BriefSource} together in one transaction, so a failure on either
   * insert leaves neither behind. {@code OrganizationValidator} already checks name and path uniqueness before this is
   * ever called, but that check and this insert are not atomic with each other: a second caller can race between the
   * two and still reach this method with a name or path that just became taken. A unique-constraint violation from that
   * race is translated into a {@link ValidationException}, so a racing caller sees the exact same error shape as one
   * that fails the up-front check instead of a raw jOOQ exception.
   *
   * @param organization The Organization to insert.
   * @param source       Its Brief source.
   * @throws ValidationException if the transaction violates the unique constraint on the Organization's name or the
   *                             source's path.
   */
  public void createOrganizationWithSource(Organization organization, BriefSource source) {
    try {
      dsl.transaction(config -> {
        var tx = DSL.using(config);
        insertOrganization(tx, organization);
        insertSource(tx, source);
      });
    } catch (DataAccessException e) {
      throw translateUniqueViolation(e, organization, source);
    }
  }

  public void deleteOrganization(UUID id) {
    dsl.deleteFrom(ORGANIZATIONS).where(ORGANIZATIONS.ID.eq(id)).execute();
  }

  public DSLContext dsl() {
    return dsl;
  }

  public Optional<Brief> findBrief(UUID organizationId, int version) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .and(BRIEFS.VERSION.eq(version))
              .fetchOptional(DatabaseService::toBrief);
  }

  public Optional<Brief> findLatestBrief(UUID organizationId) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .orderBy(BRIEFS.VERSION.desc())
              .limit(1)
              .fetchOptional(DatabaseService::toBrief);
  }

  public Optional<Organization> findOrganization(UUID id) {
    return dsl.selectFrom(ORGANIZATIONS).where(ORGANIZATIONS.ID.eq(id)).fetchOptional(DatabaseService::toOrganization);
  }

  /**
   * Case-insensitive, matching the {@code organizations_uk_name} unique index on {@code LOWER(name)}.
   *
   * <p>Postgres lowercases <em>both</em> sides, deliberately. Lowercasing the argument in Java instead would put
   * two different case-folding implementations on the two sides of the comparison, and names are display text with
   * no character-set restriction, so they can contain the characters those two disagree about. Any disagreement
   * shows up as this check reporting a name free that the unique index then rejects, or as two Organizations that
   * render identically in the admin UI. Folding both sides with the same function the index uses makes that
   * impossible rather than unlikely.
   *
   * @param name The name to look for, in any case.
   * @return The Organization, if one is registered under that name.
   */
  public Optional<Organization> findOrganizationByName(String name) {
    return dsl.selectFrom(ORGANIZATIONS)
              .where(DSL.lower(ORGANIZATIONS.NAME).eq(DSL.lower(DSL.val(name == null ? null : name.trim()))))
              .fetchOptional(DatabaseService::toOrganization);
  }

  public Optional<BriefSource> findSource(UUID organizationId) {
    return dsl.selectFrom(BRIEF_SOURCES)
              .where(BRIEF_SOURCES.ORGANIZATION_ID.eq(organizationId))
              .fetchOptional(DatabaseService::toSource);
  }

  public Optional<BriefSource> findSourceByPath(String path) {
    return dsl.selectFrom(BRIEF_SOURCES).where(BRIEF_SOURCES.PATH.eq(path)).fetchOptional(DatabaseService::toSource);
  }

  /**
   * Inserts a new Brief version, assigning {@code MAX(version) + 1} for the Organization in the {@code INSERT} itself
   * rather than reading it first. The previous shape was a {@code SELECT MAX(...)} followed by a separate
   * {@code INSERT}, which held a stale maximum across a full application round trip; two builds could read the same
   * number, and the loser only found out when the unique constraint rejected it.
   *
   * <p>The sub-select narrows that window to the statement rather than closing it: under {@code READ COMMITTED}
   * two concurrent statements can still evaluate it against the same snapshot and produce the same number. The
   * {@code UNIQUE (organization_id, version)} constraint remains the thing that makes a duplicate impossible, and the
   * loser still fails and retries on its next cycle. Genuinely serializing the assignment needs a per- Organization
   * advisory lock or {@code SERIALIZABLE} isolation, neither of which is worth it while a single poller thread is the
   * only writer.
   *
   * <p>Everything the row needs is already on the Brief, so nothing is passed alongside it: the owning
   * Organization, the checksum, the commit it was built from, and when it was built. The version and the row id are
   * this method's to assign — the version because only the database can assign it, and the id because nothing outside
   * this class ever refers to a Brief by it.
   *
   * <p>The document is written without {@code version}, {@code sourceCommit} or {@code insertInstant}. All three
   * are columns, and {@link #toBrief} puts them back on the way out, so each is stored once rather than once in a
   * column and again inside the JSON. Leaving the version out is what lets the database assign it: a document carrying
   * the number would have to be serialized before the {@code INSERT} that decides it.
   *
   * @param brief The Brief to store. Its {@code version} is ignored.
   * @return The stored Brief, carrying the version that was assigned.
   */
  public Brief insertBrief(Brief brief) {
    var organizationId = brief.organization().id();
    Brief document = new Brief(brief.checksum(), brief.organization(), null, brief.files(), null, null);
    var version = dsl.insertInto(BRIEFS)
                     .set(BRIEFS.ID, UUID.randomUUID())
                     .set(BRIEFS.ORGANIZATION_ID, organizationId)
                     .set(BRIEFS.VERSION,
                         DSL.field(
                             DSL.select(
                                    DSL.coalesce(DSL.max(BRIEFS.VERSION), 0).plus(1)
                                )
                                .from(BRIEFS)
                                .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
                         )
                     )
                     .set(BRIEFS.CHECKSUM, brief.checksum())
                     .set(BRIEFS.DOCUMENT, BriefJSON.toJSON(document))
                     .set(BRIEFS.SOURCE_COMMIT, brief.sourceCommit())
                     .set(BRIEFS.INSERT_INSTANT, brief.insertInstant())
                     .returningResult(BRIEFS.VERSION)
                     .fetchOne(0, int.class);

    return new Brief(brief.checksum(), brief.organization(), version, brief.files(), brief.sourceCommit(), brief.insertInstant());
  }

  public void insertOrganization(Organization organization) {
    insertOrganization(dsl, organization);
  }

  public void insertSource(BriefSource source) {
    insertSource(dsl, source);
  }

  /**
   * @return The latest Brief version number for every Organization that has one, keyed by Organization id. No
   *     documents, because the only caller renders the number into a listing cell — see {@link #latestBriefs()} for why
   *     loading the documents to do that would be ruinous.
   */
  public Map<UUID, Integer> latestBriefVersions() {
    var latestVersion = DSL.max(BRIEFS.VERSION);
    return dsl.select(BRIEFS.ORGANIZATION_ID, latestVersion)
              .from(BRIEFS)
              .groupBy(BRIEFS.ORGANIZATION_ID)
              .fetchMap(BRIEFS.ORGANIZATION_ID, latestVersion);
  }

  /**
   * {@code DISTINCT ON} rather than fetching the table and reducing it in Java. {@code briefs} is insert-only and never
   * pruned (§9.3), so it grows without bound for the life of the installation, and this method is on the hot path of
   * {@code POST /api/v1/briefing}, which every Handler in the fleet polls on an interval. Materialising every
   * historical document on every poll only to discard all but the newest per Organization is unbounded work
   * proportional to the whole history, for a result whose size is proportional to the Organization count. The
   * {@code ORDER BY} is deliberately spelled as exactly the
   * {@code briefs_idx_organization_version (organization_id, version DESC)} index, so the plan is a {@code Unique} over
   * an ordered index scan with no sort step — verified directly with {@code EXPLAIN} against this schema.
   *
   * @return The latest Brief version for every Organization that has one, keyed by Organization id. Documents are
   *     included, because this is what the Briefing API serves.
   */
  public Map<UUID, Brief> latestBriefs() {
    var latest = new HashMap<UUID, Brief>();
    dsl.select(BRIEFS.fields())
       .distinctOn(BRIEFS.ORGANIZATION_ID)
       .from(BRIEFS)
       .orderBy(BRIEFS.ORGANIZATION_ID, BRIEFS.VERSION.desc())
       .fetch()
       .forEach(r -> latest.put(r.get(BRIEFS.ORGANIZATION_ID), toBrief(r)));
    return latest;
  }

  /**
   * @param organizationId The Organization whose history to read.
   * @return Every version of an Organization's Brief, newest first, each one whole. The version list is the way into
   *     the per-version pages, so the caller is one click away from wanting the files anyway.
   */
  public List<Brief> listBriefs(UUID organizationId) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .orderBy(BRIEFS.VERSION.desc())
              .fetch(DatabaseService::toBrief);
  }

  public List<Organization> listOrganizations() {
    return dsl.selectFrom(ORGANIZATIONS).orderBy(ORGANIZATIONS.NAME).fetch(DatabaseService::toOrganization);
  }

  public List<BriefSource> listSources() {
    return dsl.selectFrom(BRIEF_SOURCES).fetch(DatabaseService::toSource);
  }

  /**
   * @param organizationId    The Organization whose source is being updated.
   * @param lastBuiltCommit   The commit the Brief was last built from.
   * @param lastPolledInstant When the source was last polled.
   * @param status            The status the poll produced.
   * @param lastError         The build error, or {@code null}.
   * @param lastPullError     The {@code git pull} error, or {@code null}.
   * @param updateInstant     When the row was updated. Taken as a parameter rather than read from the clock here, like
   *                          every other write method on this class, so the caller can record one instant across a
   *                          whole cycle instead of a set of times that drift apart by however long the writes took.
   */
  public void updateSourceStatus(UUID organizationId, String lastBuiltCommit, Instant lastPolledInstant,
                                 SourceStatus status, String lastError, String lastPullError, Instant updateInstant) {
    dsl.update(BRIEF_SOURCES)
       .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, lastBuiltCommit)
       .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, lastPolledInstant)
       .set(BRIEF_SOURCES.LAST_STATUS, status)
       .set(BRIEF_SOURCES.LAST_ERROR, lastError)
       .set(BRIEF_SOURCES.LAST_PULL_ERROR, lastPullError)
       .set(BRIEF_SOURCES.UPDATE_INSTANT, updateInstant)
       .where(BRIEF_SOURCES.ORGANIZATION_ID.eq(organizationId))
       .execute();
  }
}
