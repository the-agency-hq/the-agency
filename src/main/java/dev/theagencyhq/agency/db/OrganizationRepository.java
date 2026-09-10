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
import org.jooq.Condition;

import static dev.theagencyhq.agency.db.jooq.Tables.*;

// Single-type Member on top of the module imports above, because java.base also exports a Member
// (java.lang.reflect), and two module imports of the same name make every unqualified use ambiguous.

/**
 * The {@code organizations} table. Whole rows in and whole rows out: an {@link Organization} is the row, so a write is
 * {@link #create} or {@link #update} of one, never a column at a time — whatever business decision produced the row
 * belongs to the service that made it, and a change that has to land together with a write to another table is composed
 * by that service inside {@code Database.transaction}.
 */
@Prototype
public class OrganizationRepository {
  private final DSLContext dsl;

  public OrganizationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Agents fromAgentsColumn(JSONB column) {
    return column == null ? null : AgentsJSON.fromJSON(column.data());
  }

  // Null for every Agent rather than a document listing all of them, so a selection that was never narrowed is
  // NULL in the column, absent from the Brief document, and invisible to every checksum computed before the
  // column existed.
  private static JSONB toAgentsColumn(Agents agents) {
    return agents == null ? null : JSONB.jsonb(AgentsJSON.toJSON(agents));
  }

  private static Organization toOrganization(org.jooq.Record record) {
    return new Organization(
        record.get(ORGANIZATIONS.ID),
        record.get(ORGANIZATIONS.NAME),
        fromAgentsColumn(record.get(ORGANIZATIONS.AGENTS)),
        record.get(ORGANIZATIONS.INSERT_INSTANT),
        record.get(ORGANIZATIONS.UPDATE_INSTANT));
  }

  // Translates the name's unique-constraint violation into the same ValidationException shape the validator's
  // up-front check throws, keyed off Postgres's own reported constraint name (verified directly against this
  // schema) rather than parsing the exception's free-text message, which is not a stable contract across Postgres
  // versions. Any other DataAccessException is not ours to interpret, so it is returned unchanged for the caller to
  // rethrow as-is.
  //
  // The up-front check is non-atomic with the write that follows it -- a second caller can take the name in
  // between -- so this is the only thing that makes a duplicate genuinely impossible rather than merely unlikely,
  // and the racing caller sees the same error the losing form submission would have shown.
  private static RuntimeException translateUniqueViolation(DataAccessException e, String name) {
    var postgres = e.getCause(PSQLException.class);
    var constraint = postgres == null || postgres.getServerErrorMessage() == null ? null
        : postgres.getServerErrorMessage().getConstraint();
    if ("organizations_uk_name".equals(constraint)) {
      return new ValidationException(List.of("The name [" + name + "] is already registered."));
    }

    return e;
  }

  /**
   * @param organization The Organization to insert, whole.
   * @throws ValidationException if another Organization already holds the name, case-insensitively.
   */
  public void create(Organization organization) {
    try {
      dsl.insertInto(ORGANIZATIONS)
         .set(ORGANIZATIONS.ID, organization.id())
         .set(ORGANIZATIONS.NAME, organization.name())
         .set(ORGANIZATIONS.AGENTS, toAgentsColumn(organization.agents()))
         .set(ORGANIZATIONS.INSERT_INSTANT, organization.insertInstant())
         .set(ORGANIZATIONS.UPDATE_INSTANT, organization.updateInstant())
         .execute();
    } catch (DataAccessException e) {
      throw translateUniqueViolation(e, organization.name());
    }
  }

  /**
   * Idempotent: deleting an Organization that does not exist deletes nothing. The members, source, and Briefs go with
   * it, by the schema's cascades.
   *
   * @param id The Organization.
   */
  public void delete(UUID id) {
    dsl.deleteFrom(ORGANIZATIONS).where(ORGANIZATIONS.ID.eq(id)).execute();
  }

  /**
   * @return Every Organization, in name order.
   */
  public List<Organization> findAll() {
    return dsl.selectFrom(ORGANIZATIONS).orderBy(ORGANIZATIONS.NAME).fetch(OrganizationRepository::toOrganization);
  }

  /**
   * @param userId The user whose memberships drive the result.
   * @return The Organizations the user holds a membership row in, in either state, in name order. The admin UI reads
   *     this so an invited user can find the Organization and accept.
   */
  public List<Organization> findAllByMember(UUID userId) {
    return findAllByMember(userId, null);
  }

  /**
   * @param userId The user whose memberships drive the result.
   * @param state  Narrow to memberships in this state, or {@code null} for all of them. The APIs pass
   *               {@link MembershipState#ACTIVE} because an invitation someone has not accepted entitles their Handler
   *               to nothing.
   * @return The matching Organizations, in name order.
   */
  public List<Organization> findAllByMember(UUID userId, MembershipState state) {
    Condition where = MEMBERS.USER_ID.eq(userId);
    if (state != null) {
      where = where.and(MEMBERS.STATE.eq(state));
    }

    return dsl.select(ORGANIZATIONS.fields())
              .from(ORGANIZATIONS)
              .join(MEMBERS).on(MEMBERS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
              .where(where)
              .orderBy(ORGANIZATIONS.NAME)
              .fetch(OrganizationRepository::toOrganization);
  }

  public Optional<Organization> findById(UUID id) {
    return dsl.selectFrom(ORGANIZATIONS)
              .where(ORGANIZATIONS.ID.eq(id))
              .fetchOptional(OrganizationRepository::toOrganization);
  }

  /**
   * Case-insensitive, matching the {@code organizations_uk_name} unique index on {@code LOWER(name)}.
   *
   * <p>Postgres lowercases <em>both</em> sides, deliberately. Lowercasing the argument in Java instead would put
   * two different case-folding implementations on the two sides of the comparison, and names are display text with no
   * character-set restriction, so they can contain the characters those two disagree about. Any disagreement shows up
   * as this check reporting a name free that the unique index then rejects, or as two Organizations that render
   * identically in the admin UI. Folding both sides with the same function the index uses makes that impossible rather
   * than unlikely.
   *
   * @param name The name to look for, in any case.
   * @return The Organization, if one is registered under that name.
   */
  public Optional<Organization> findByName(String name) {
    return dsl.selectFrom(ORGANIZATIONS)
              .where(DSL.lower(ORGANIZATIONS.NAME).eq(DSL.lower(DSL.val(name == null ? null : name.trim()))))
              .fetchOptional(OrganizationRepository::toOrganization);
  }

  /**
   * Writes an Organization's row from the model, whole: everything but the id and the insert instant, which never
   * change.
   *
   * @param organization The Organization as it should now be stored.
   * @return True if the Organization exists and was written.
   * @throws ValidationException if another Organization already holds the name, case-insensitively.
   */
  public boolean update(Organization organization) {
    try {
      return dsl.update(ORGANIZATIONS)
                .set(ORGANIZATIONS.NAME, organization.name())
                .set(ORGANIZATIONS.AGENTS, toAgentsColumn(organization.agents()))
                .set(ORGANIZATIONS.UPDATE_INSTANT, organization.updateInstant())
                .where(ORGANIZATIONS.ID.eq(organization.id()))
                .execute() == 1;
    } catch (DataAccessException e) {
      throw translateUniqueViolation(e, organization.name());
    }
  }
}
