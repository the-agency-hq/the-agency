/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.db;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;
import module org.jooq;
import module org.postgresql.jdbc;

import dev.theagencyhq.agency.model.internal.*;

import static dev.theagencyhq.agency.db.jooq.Tables.*;

/**
 * The {@code brief_sources} table. Whole rows in and whole rows out: a {@link BriefSource} is the row, its
 * configuration document included, so a write is {@link #create}, {@link #update}, or {@link #upsert} of one — never
 * a member of the document at a time. Which members change, and what is carried over from the row that was there,
 * is the business of the service that decided it.
 */
@Prototype
public class BriefSourceRepository {
  private final DSLContext dsl;

  public BriefSourceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static JSONB toConfigColumn(BriefSourceConfig config) {
    return JSONB.jsonb(BriefSourceConfigJSON.toJSON(config));
  }

  private static BriefSource toSource(org.jooq.Record record) {
    return new BriefSource(
        record.get(BRIEF_SOURCES.ID),
        record.get(BRIEF_SOURCES.ORGANIZATION_ID),
        // The type and source columns beside the document are for SQL; the document is the configuration, and
        // both of them are read off it.
        BriefSourceConfigJSON.fromJSON(record.get(BRIEF_SOURCES.SOURCE_CONFIG).data()),
        record.get(BRIEF_SOURCES.LAST_BUILT_COMMIT),
        record.get(BRIEF_SOURCES.LAST_POLLED_INSTANT),
        record.get(BRIEF_SOURCES.LAST_STATUS),
        record.get(BRIEF_SOURCES.LAST_ERROR),
        record.get(BRIEF_SOURCES.INSERT_INSTANT),
        record.get(BRIEF_SOURCES.UPDATE_INSTANT));
  }

  // Translates the identity's unique-constraint violation into the same ValidationException shape the validator's
  // up-front check throws, keyed off Postgres's own reported constraint name (verified directly against this
  // schema) rather than parsing the exception's free-text message, which is not a stable contract across Postgres
  // versions. Any other DataAccessException is not ours to interpret, so it is returned unchanged for the caller to
  // rethrow as-is.
  //
  // The up-front check is non-atomic with the write that follows it -- a second caller can take the repository in
  // between -- so this is the only thing that makes a duplicate genuinely impossible rather than merely unlikely,
  // and the racing caller sees the same error the losing form submission would have shown.
  private static RuntimeException translateUniqueViolation(DataAccessException e, String source) {
    var postgres = e.getCause(PSQLException.class);
    var constraint = postgres == null || postgres.getServerErrorMessage() == null ? null
        : postgres.getServerErrorMessage().getConstraint();
    if ("brief_sources_uk_source".equals(constraint) && source != null) {
      return new ValidationException(
          List.of("The source [" + source + "] is already registered to another Organization."));
    }

    return e;
  }

  /**
   * @param source The source to insert, whole.
   * @throws ValidationException if another Organization already holds the source's identity, case-insensitively.
   */
  public void create(BriefSource source) {
    try {
      dsl.insertInto(BRIEF_SOURCES)
         .set(BRIEF_SOURCES.ID, source.id())
         .set(BRIEF_SOURCES.ORGANIZATION_ID, source.organizationId())
         .set(BRIEF_SOURCES.TYPE, source.type())
         .set(BRIEF_SOURCES.SOURCE, source.source())
         .set(BRIEF_SOURCES.SOURCE_CONFIG, toConfigColumn(source.config()))
         .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, source.lastBuiltCommit())
         .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, source.lastPolledInstant())
         .set(BRIEF_SOURCES.LAST_STATUS, source.lastStatus())
         .set(BRIEF_SOURCES.LAST_ERROR, source.lastError())
         .set(BRIEF_SOURCES.INSERT_INSTANT, source.insertInstant())
         .set(BRIEF_SOURCES.UPDATE_INSTANT, source.updateInstant())
         .execute();
    } catch (DataAccessException e) {
      throw translateUniqueViolation(e, source.source());
    }
  }

  /**
   * Idempotent: deleting a source that does not exist deletes nothing.
   *
   * @param id The source.
   */
  public void delete(UUID id) {
    dsl.deleteFrom(BRIEF_SOURCES).where(BRIEF_SOURCES.ID.eq(id)).execute();
  }

  public List<BriefSource> findAll() {
    return dsl.selectFrom(BRIEF_SOURCES).fetch(BriefSourceRepository::toSource);
  }

  public Optional<BriefSource> findById(UUID id) {
    return dsl.selectFrom(BRIEF_SOURCES)
              .where(BRIEF_SOURCES.ID.eq(id))
              .fetchOptional(BriefSourceRepository::toSource);
  }

  /**
   * @param organizationId The Organization.
   * @return Its source, if it has one. At most one: the column is unique.
   */
  public Optional<BriefSource> findByOrganizationId(UUID organizationId) {
    return dsl.selectFrom(BRIEF_SOURCES)
              .where(BRIEF_SOURCES.ORGANIZATION_ID.eq(organizationId))
              .fetchOptional(BriefSourceRepository::toSource);
  }

  /**
   * Case-insensitive, matching the {@code brief_sources_uk_source} unique index, and lowercased by Postgres on both
   * sides for the same reason {@link OrganizationRepository#findByName} is: two different case-folding
   * implementations either side of a comparison is how a check reports a source free that the index then rejects.
   *
   * @param type   The kind of source.
   * @param source The identity the kind is unique by — for GitHub, {@code owner/repository} — in any case.
   * @return The source, if that identity is registered to an Organization.
   */
  public Optional<BriefSource> findBySource(BriefSourceType type, String source) {
    return dsl.selectFrom(BRIEF_SOURCES)
              .where(BRIEF_SOURCES.TYPE.eq(type))
              .and(DSL.lower(BRIEF_SOURCES.SOURCE).eq(DSL.lower(DSL.val(source == null ? null : source.trim()))))
              .fetchOptional(BriefSourceRepository::toSource);
  }

  /**
   * Writes a source's row from the model, whole: everything but the id, the Organization, and the insert instant,
   * which never change.
   *
   * @param source The source as it should now be stored.
   * @return True if the source exists and was written.
   * @throws ValidationException if another Organization already holds the source's identity, case-insensitively.
   */
  public boolean update(BriefSource source) {
    try {
      return dsl.update(BRIEF_SOURCES)
                .set(BRIEF_SOURCES.TYPE, source.type())
                .set(BRIEF_SOURCES.SOURCE, source.source())
                .set(BRIEF_SOURCES.SOURCE_CONFIG, toConfigColumn(source.config()))
                .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, source.lastBuiltCommit())
                .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, source.lastPolledInstant())
                .set(BRIEF_SOURCES.LAST_STATUS, source.lastStatus())
                .set(BRIEF_SOURCES.LAST_ERROR, source.lastError())
                .set(BRIEF_SOURCES.UPDATE_INSTANT, source.updateInstant())
                .where(BRIEF_SOURCES.ID.eq(source.id()))
                .execute() == 1;
    } catch (DataAccessException e) {
      throw translateUniqueViolation(e, source.source());
    }
  }

  /**
   * Inserts the source, or replaces the Organization's existing one with it. One Organization holds one source, so
   * the Organization is the identity the row is matched on: a replaced row keeps its own id and insert instant and
   * takes everything else from the model.
   *
   * @param source The source as it should now be stored.
   * @throws ValidationException if another Organization already holds the source's identity, case-insensitively.
   */
  public void upsert(BriefSource source) {
    try {
      dsl.insertInto(BRIEF_SOURCES)
         .set(BRIEF_SOURCES.ID, source.id())
         .set(BRIEF_SOURCES.ORGANIZATION_ID, source.organizationId())
         .set(BRIEF_SOURCES.TYPE, source.type())
         .set(BRIEF_SOURCES.SOURCE, source.source())
         .set(BRIEF_SOURCES.SOURCE_CONFIG, toConfigColumn(source.config()))
         .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, source.lastBuiltCommit())
         .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, source.lastPolledInstant())
         .set(BRIEF_SOURCES.LAST_STATUS, source.lastStatus())
         .set(BRIEF_SOURCES.LAST_ERROR, source.lastError())
         .set(BRIEF_SOURCES.INSERT_INSTANT, source.insertInstant())
         .set(BRIEF_SOURCES.UPDATE_INSTANT, source.updateInstant())
         .onConflict(BRIEF_SOURCES.ORGANIZATION_ID)
         .doUpdate()
         .set(BRIEF_SOURCES.TYPE, DSL.excluded(BRIEF_SOURCES.TYPE))
         .set(BRIEF_SOURCES.SOURCE, DSL.excluded(BRIEF_SOURCES.SOURCE))
         .set(BRIEF_SOURCES.SOURCE_CONFIG, DSL.excluded(BRIEF_SOURCES.SOURCE_CONFIG))
         .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, DSL.excluded(BRIEF_SOURCES.LAST_BUILT_COMMIT))
         .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, DSL.excluded(BRIEF_SOURCES.LAST_POLLED_INSTANT))
         .set(BRIEF_SOURCES.LAST_STATUS, DSL.excluded(BRIEF_SOURCES.LAST_STATUS))
         .set(BRIEF_SOURCES.LAST_ERROR, DSL.excluded(BRIEF_SOURCES.LAST_ERROR))
         .set(BRIEF_SOURCES.UPDATE_INSTANT, DSL.excluded(BRIEF_SOURCES.UPDATE_INSTANT))
         .execute();
    } catch (DataAccessException e) {
      throw translateUniqueViolation(e, source.source());
    }
  }
}
