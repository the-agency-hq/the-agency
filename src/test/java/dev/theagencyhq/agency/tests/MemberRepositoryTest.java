/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import dev.theagencyhq.agency.model.Member;

import static org.testng.Assert.*;

/**
 * {@code MemberRepository} against the real database: every operation, and both directions of the key — the
 * Organization's members and the user's memberships.
 */
@Test
public class MemberRepositoryTest extends BaseTest {
  @Test
  public void createsReadsUpdatesAndDeletes() {
    var organization = insertOrganization();
    var owner = members.findByOrganizationIdAndUserId(organization.id(), testUser.userId()).orElseThrow();
    assertEquals(owner.role(), Role.OWNER);
    assertEquals(owner.state(), MembershipState.ACTIVE);

    var invited = new Member(organization.id(), ordinaryUser.userId(), Role.CONTRIBUTOR, MembershipState.PENDING,
        testUser.userId(), TEST_INSTANT, null);
    members.create(invited);
    assertEquals(members.findByOrganizationIdAndUserId(organization.id(), ordinaryUser.userId()).orElseThrow(),
        invited);
    assertTrue(members.findByOrganizationIdAndUserId(organization.id(), UUID.randomUUID()).isEmpty());
    assertEquals(new HashSet<>(members.findAllByOrganizationId(organization.id())), Set.of(owner, invited));

    // Accepting: the whole row from the model, the key untouched.
    var joined = invited.withState(MembershipState.ACTIVE, TEST_INSTANT.plusSeconds(60));
    assertTrue(members.update(joined));
    assertEquals(members.findByOrganizationIdAndUserId(organization.id(), ordinaryUser.userId()).orElseThrow(),
        joined);

    // Promoting.
    var promoted = joined.withRole(Role.OWNER);
    assertTrue(members.update(promoted));
    assertEquals(members.findByOrganizationIdAndUserId(organization.id(), ordinaryUser.userId()).orElseThrow(),
        promoted);

    // A row that is not there is not written.
    assertFalse(members.update(new Member(organization.id(), UUID.randomUUID(), Role.OWNER, MembershipState.ACTIVE,
        null, null, TEST_INSTANT)));

    members.delete(organization.id(), ordinaryUser.userId());
    assertTrue(members.findByOrganizationIdAndUserId(organization.id(), ordinaryUser.userId()).isEmpty());
    members.delete(organization.id(), ordinaryUser.userId());
  }

  /**
   * The narrowed listing the last-owner rules count, and the user's own memberships across Organizations, which
   * the listing page splits into invitations and rows.
   */
  @Test
  public void findsByRoleAndStateAndByUser() {
    var first = insertOrganization();
    var second = insertOrganization();
    var invited = insertMember(first, ordinaryUser, Role.OWNER, MembershipState.PENDING);
    var active = insertMember(second, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);

    // The test user is the ACTIVE OWNER of both; the invited OWNER of the first is PENDING and does not count.
    assertEquals(members.findAllByOrganizationId(first.id(), Role.OWNER, MembershipState.ACTIVE).size(), 1);
    assertEquals(members.findAllByOrganizationId(first.id(), Role.OWNER, MembershipState.PENDING), List.of(invited));
    assertEquals(members.findAllByOrganizationId(second.id(), Role.CONTRIBUTOR, MembershipState.ACTIVE),
        List.of(active));
    assertTrue(members.findAllByOrganizationId(second.id(), Role.CONTRIBUTOR, MembershipState.PENDING).isEmpty());

    assertEquals(new HashSet<>(members.findAllByUserId(ordinaryUser.userId())), Set.of(invited, active));
    assertEquals(members.findAllByUserId(testUser.userId()).size(), 2);
    assertTrue(members.findAllByUserId(UUID.randomUUID()).isEmpty());
  }
}
