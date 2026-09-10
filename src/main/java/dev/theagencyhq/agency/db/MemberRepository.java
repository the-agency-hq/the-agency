/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.db;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;
import module org.jooq;

import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.Role;

import static dev.theagencyhq.agency.db.jooq.Tables.*;

// Single-type Member and Role, on top of the module imports above, because java.base also exports a Member
// (java.lang.reflect) and org.jooq a Role, and two module imports of the same name make every unqualified use
// ambiguous.

/**
 * The {@code members} table, keyed by Organization and user. Whole rows in and whole rows out: a {@link Member} is
 * the row, so a write is {@link #create} or {@link #update} of one, never a column at a time — which transition
 * a row is making, and whether it is allowed to, belongs to {@code MembershipService} and its validator.
 *
 * <p>The row holds the user's FusionAuth id and nothing else about them; a {@link Member} read here carries a
 * {@link User} with no email, which the service enriches from FusionAuth when a page needs it.
 */
@Prototype
public class MemberRepository {
  private final DSLContext dsl;

  public MemberRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Member toMember(org.jooq.Record record) {
    return new Member(
        record.get(MEMBERS.ORGANIZATION_ID),
        record.get(MEMBERS.USER_ID),
        record.get(MEMBERS.ROLE),
        record.get(MEMBERS.STATE),
        record.get(MEMBERS.INVITED_BY),
        record.get(MEMBERS.INVITED_AT),
        record.get(MEMBERS.JOINED_AT));
  }

  /**
   * @param member The membership row to insert, whole.
   */
  public void create(Member member) {
    dsl.insertInto(MEMBERS)
       .set(MEMBERS.ORGANIZATION_ID, member.organizationId())
       .set(MEMBERS.USER_ID, member.userId())
       .set(MEMBERS.ROLE, member.role())
       .set(MEMBERS.STATE, member.state())
       .set(MEMBERS.INVITED_BY, member.invitedBy())
       .set(MEMBERS.INVITED_AT, member.invitedAt())
       .set(MEMBERS.JOINED_AT, member.joinedAt())
       .execute();
  }

  /**
   * Idempotent: removing a row that does not exist removes nothing. One method serves declining an invitation,
   * cancelling one, removing a member, and leaving — all four end with the row gone.
   *
   * @param organizationId The Organization.
   * @param userId         The member's FusionAuth user UUID.
   */
  public void delete(UUID organizationId, UUID userId) {
    dsl.deleteFrom(MEMBERS)
       .where(MEMBERS.ORGANIZATION_ID.eq(organizationId))
       .and(MEMBERS.USER_ID.eq(userId))
       .execute();
  }

  public List<Member> findAllByOrganizationId(UUID organizationId) {
    return dsl.selectFrom(MEMBERS)
              .where(MEMBERS.ORGANIZATION_ID.eq(organizationId))
              .fetch(MemberRepository::toMember);
  }

  /**
   * @param organizationId The Organization.
   * @param role           The role to narrow to.
   * @param state          The state to narrow to.
   * @return The Organization's members holding that role in that state — for {@code OWNER} and {@code ACTIVE},
   *     the set the last-owner rules count before a demotion, removal, or leave.
   */
  public List<Member> findAllByOrganizationId(UUID organizationId, Role role, MembershipState state) {
    return dsl.selectFrom(MEMBERS)
              .where(MEMBERS.ORGANIZATION_ID.eq(organizationId))
              .and(MEMBERS.ROLE.eq(role))
              .and(MEMBERS.STATE.eq(state))
              .fetch(MemberRepository::toMember);
  }

  /**
   * @param userId The user.
   * @return Every membership row the user holds, across all Organizations and in both states — the listing page reads
   *     it to split pending invitations from the Organizations the user is a member of.
   */
  public List<Member> findAllByUserId(UUID userId) {
    return dsl.selectFrom(MEMBERS)
              .where(MEMBERS.USER_ID.eq(userId))
              .fetch(MemberRepository::toMember);
  }

  public Optional<Member> findByOrganizationIdAndUserId(UUID organizationId, UUID userId) {
    return dsl.selectFrom(MEMBERS)
              .where(MEMBERS.ORGANIZATION_ID.eq(organizationId))
              .and(MEMBERS.USER_ID.eq(userId))
              .fetchOptional(MemberRepository::toMember);
  }

  /**
   * Writes a membership row from the model, whole: everything but the key.
   *
   * @param member The member as it should now be stored.
   * @return True if the row exists and was written.
   */
  public boolean update(Member member) {
    return dsl.update(MEMBERS)
              .set(MEMBERS.ROLE, member.role())
              .set(MEMBERS.STATE, member.state())
              .set(MEMBERS.INVITED_BY, member.invitedBy())
              .set(MEMBERS.INVITED_AT, member.invitedAt())
              .set(MEMBERS.JOINED_AT, member.joinedAt())
              .where(MEMBERS.ORGANIZATION_ID.eq(member.organizationId()))
              .and(MEMBERS.USER_ID.eq(member.userId()))
              .execute() == 1;
  }
}
