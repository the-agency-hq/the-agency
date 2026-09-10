/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.db;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;
import module org.jooq;

import dev.theagencyhq.agency.model.internal.*;

import static dev.theagencyhq.agency.db.jooq.Tables.*;

/**
 * The {@code briefs} table: an Organization's version history, insert-only. There is no update and no delete,
 * because a version is immutable and never pruned — the Briefing API serves whatever version a Handler asks after,
 * and a Handler can hold any version ever published.
 *
 * <p>A {@link Brief} is the row: the content-addressed document ({@code checksum}, the Organization's identity and
 * selection, the files) in the {@code document} column, and the version and provenance in columns of their own,
 * so each is stored in exactly one place. {@link #create} assigns the version.
 */
@Prototype
public class BriefRepository {
  private final DSLContext dsl;

  public BriefRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  // Parsing here rather than at each call site is what lets every caller work in Briefs and never in JSON text.
  private static Brief toBrief(org.jooq.Record record) {
    var stored = BriefJSON.fromJSON(record.get(BRIEFS.DOCUMENT));
    return new Brief(stored.checksum(), stored.organization(), record.get(BRIEFS.VERSION), stored.files(),
        record.get(BRIEFS.SOURCE_COMMIT), record.get(BRIEFS.INSERT_INSTANT));
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
   * loser still fails and retries on its next cycle. Genuinely serializing the assignment needs a per-Organization
   * advisory lock or {@code SERIALIZABLE} isolation, neither of which is worth it while a single poller thread is the
   * only writer.
   *
   * <p>The document is written without {@code version}, {@code sourceCommit} or {@code insertInstant}. All three
   * are columns, and {@link #toBrief} puts them back on the way out, so each is stored once rather than once in a
   * column and again inside the JSON. Leaving the version out is what lets the database assign it: a document carrying
   * the number would have to be serialized before the {@code INSERT} that decides it.
   *
   * @param brief The Brief to store. Its {@code version} is ignored; the row id is this method's to assign, because
   *              nothing outside this class ever refers to a Brief by it.
   * @return The stored Brief, carrying the version that was assigned.
   */
  public Brief create(Brief brief) {
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

    return new Brief(brief.checksum(), brief.organization(), version, brief.files(), brief.sourceCommit(),
        brief.insertInstant());
  }

  /**
   * @param organizationId The Organization whose history to read.
   * @return Every version of an Organization's Brief, newest first, each one whole. The version list is the way into
   *     the per-version pages, so the caller is one click away from wanting the files anyway.
   */
  public List<Brief> findAllByOrganizationId(UUID organizationId) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .orderBy(BRIEFS.VERSION.desc())
              .fetch(BriefRepository::toBrief);
  }

  public Optional<Brief> findByOrganizationIdAndVersion(UUID organizationId, int version) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .and(BRIEFS.VERSION.eq(version))
              .fetchOptional(BriefRepository::toBrief);
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
  public Map<UUID, Brief> findLatest() {
    var latest = new HashMap<UUID, Brief>();
    dsl.select(BRIEFS.fields())
       .distinctOn(BRIEFS.ORGANIZATION_ID)
       .from(BRIEFS)
       .orderBy(BRIEFS.ORGANIZATION_ID, BRIEFS.VERSION.desc())
       .fetch()
       .forEach(r -> latest.put(r.get(BRIEFS.ORGANIZATION_ID), toBrief(r)));
    return latest;
  }

  public Optional<Brief> findLatestByOrganizationId(UUID organizationId) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .orderBy(BRIEFS.VERSION.desc())
              .limit(1)
              .fetchOptional(BriefRepository::toBrief);
  }

  /**
   * @return The latest Brief version number for every Organization that has one, keyed by Organization id. No
   *     documents, because the only caller renders the number into a listing cell — see {@link #findLatest()} for why
   *     loading the documents to do that would be ruinous.
   */
  public Map<UUID, Integer> findLatestVersions() {
    var latestVersion = DSL.max(BRIEFS.VERSION);
    return dsl.select(BRIEFS.ORGANIZATION_ID, latestVersion)
              .from(BRIEFS)
              .groupBy(BRIEFS.ORGANIZATION_ID)
              .fetchMap(BRIEFS.ORGANIZATION_ID, latestVersion);
  }
}
