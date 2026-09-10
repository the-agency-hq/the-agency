/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * {@code OrganizationRepository} against the real database: every operation, the case-insensitive name rules the
 * unique index enforces, and the membership join the listing and both APIs read through.
 */
@Test
public class OrganizationRepositoryTest extends BaseTest {
  @Test
  public void createsReadsUpdatesAndDeletes() {
    var name = "Acme-" + UUID.randomUUID();
    var organization = new Organization(UUID.randomUUID(), name, null, TEST_INSTANT, TEST_INSTANT);
    organizations.create(organization);

    assertEquals(organizations.findById(organization.id()).orElseThrow(), organization);
    assertEquals(organizations.findByName(name.toUpperCase(Locale.ROOT)).orElseThrow(), organization);
    assertEquals(organizations.findByName("  " + name + " ").orElseThrow(), organization);
    assertTrue(organizations.findByName("nobody-" + UUID.randomUUID()).isEmpty());

    // The whole row, from the model: name, selection, and update instant move; the insert instant does not.
    var later = TEST_INSTANT.plusSeconds(60);
    var updated = new Organization(organization.id(), "Renamed-" + UUID.randomUUID(), new Agents(List.of(Agent.CLAUDE)),
        later, later);
    assertTrue(organizations.update(updated));
    var stored = organizations.findById(organization.id()).orElseThrow();
    assertEquals(stored.name(), updated.name());
    assertEquals(stored.agents(), new Agents(List.of(Agent.CLAUDE)));
    assertEquals(stored.insertInstant(), TEST_INSTANT);
    assertEquals(stored.updateInstant(), later);
    assertTrue(organizations.findByName(name).isEmpty());

    // Back to every Agent: NULL in the column, null on the model.
    assertTrue(organizations.update(new Organization(organization.id(), updated.name(), null, later, later)));
    assertNull(organizations.findById(organization.id()).orElseThrow().agents());

    assertFalse(organizations.update(new Organization(UUID.randomUUID(), "ghost", null, later, later)));

    organizations.delete(organization.id());
    assertTrue(organizations.findById(organization.id()).isEmpty());
    organizations.delete(organization.id());
  }

  /**
   * The membership join, in both of its shapes: every row for the listing (so an invitee can find the Organization
   * to accept), ACTIVE only for the APIs (an unaccepted invitation entitles a Handler to nothing). Name order in
   * both, like {@code findAll}.
   */
  @Test
  public void findsByMember() {
    var active = insertOrganization("member-b-" + UUID.randomUUID());
    var invited = insertOrganization("member-a-" + UUID.randomUUID());
    var foreign = new Organization(UUID.randomUUID(), "member-c-" + UUID.randomUUID(), null, TEST_INSTANT,
        TEST_INSTANT);
    organizations.create(foreign);
    insertMember(active, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);
    insertMember(invited, ordinaryUser, Role.OWNER, MembershipState.PENDING);

    assertEquals(organizations.findAllByMember(ordinaryUser.userId()), List.of(invited, active));
    assertEquals(organizations.findAllByMember(ordinaryUser.userId(), MembershipState.ACTIVE), List.of(active));
    assertEquals(organizations.findAllByMember(ordinaryUser.userId(), MembershipState.PENDING), List.of(invited));
    assertEquals(organizations.findAllByMember(UUID.randomUUID()), List.of());
    assertEquals(organizations.findAll(), List.of(invited, active, foreign));
  }

  /**
   * Names are unique case-insensitively, on create and on update alike, and the violation surfaces as the same
   * {@code ValidationException} the validator's up-front check throws — because the index, not the check, is what
   * makes a duplicate impossible.
   */
  @Test
  public void rejectsADuplicateNameInAnyCase() {
    var taken = insertOrganization("Taken-" + UUID.randomUUID());
    var other = insertOrganization("Other-" + UUID.randomUUID());

    var onCreate = expectThrows(ValidationException.class, () -> organizations.create(
        new Organization(UUID.randomUUID(), taken.name().toUpperCase(Locale.ROOT), null, TEST_INSTANT, TEST_INSTANT)));
    assertEquals(onCreate.errors(), List.of("The name [" + taken.name().toUpperCase(Locale.ROOT) + "] is already registered."));

    var onUpdate = expectThrows(ValidationException.class, () -> organizations.update(
        new Organization(other.id(), taken.name().toLowerCase(Locale.ROOT), null, TEST_INSTANT, TEST_INSTANT)));
    assertEquals(onUpdate.errors(), List.of("The name [" + taken.name().toLowerCase(Locale.ROOT) + "] is already registered."));
    assertEquals(organizations.findById(other.id()).orElseThrow().name(), other.name());
  }
}
