/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

@Test
public class BriefRepositoryTest extends BaseTest {

  @Test
  public void insertsAndReadsTheWholeGraph() {
    var now = Instant.ofEpochMilli(1_700_000_000_000L);
    var organization = new Organization(UUID.randomUUID(), "Acme-" + UUID.randomUUID(), null, now, now);
    organizations.create(organization);

    assertEquals(organizations.findById(organization.id()).orElseThrow().name(), organization.name());
    assertEquals(organizations.findByName(organization.name().toUpperCase(Locale.ROOT))
                         .orElseThrow().id(), organization.id());

    // insertBrief takes a Brief and owns the serialization, so what goes into the document column and what comes
    // back out of it are the same codec -- the round trip below is what proves that.
    assertEquals(briefs.create(new Brief("sum-1", organization, null, List.of(), "abc123", now))
                         .version().intValue(), 1);
    assertEquals(briefs.create(new Brief("sum-2", organization, null, List.of(), "def456", now))
                         .version().intValue(), 2);

    var latest = briefs.findLatestByOrganizationId(organization.id()).orElseThrow();
    assertEquals(latest.version().intValue(), 2);
    assertEquals(latest.checksum(), "sum-2");
    assertEquals(latest.organization(), organization);

    // Provenance comes off the row, not out of the stored document -- the Brief handed to insertBrief carried
    // neither, so reading them back proves BriefRepository attaches them.
    assertEquals(latest.sourceCommit(), "def456");
    assertEquals(latest.insertInstant(), now);

    // The version is a column, never a member of the stored JSON -- that is what lets the INSERT assign it in a
    // sub-select instead of the application reading MAX(version) first and racing between the two statements.
    // Provenance is out for the same reason: one place per fact.
    var document = database.dsl()
                           .resultQuery("SELECT document FROM briefs WHERE organization_id = ? AND version = 2",
                               organization.id())
                           .fetchOne(0, String.class);
    var storedShape = Brief.fromJSON(document);
    assertNull(storedShape.version(), document);
    assertNull(storedShape.sourceCommit(), document);
    assertNull(storedShape.insertInstant(), document);
    assertEquals(storedShape.checksum(), "sum-2");
    assertEquals(storedShape.organization(), organization);

    assertEquals(briefs.findAllByOrganizationId(organization.id()).stream().map(Brief::version).toList(),
        List.of(2, 1));
    assertEquals(briefs.findByOrganizationIdAndVersion(organization.id(), 1).orElseThrow().version().intValue(), 1);

    // Asserts the HIGHEST version specifically (not merely presence): an ascending sort paired with
    // putIfAbsent would silently keep version 1 for every Organization instead of version 2, which this
    // Organization's two inserted versions above are set up to catch.
    var latestFromMap = briefs.findLatest().get(organization.id());
    assertEquals(latestFromMap.version().intValue(), 2);
    assertEquals(latestFromMap.checksum(), "sum-2");

    // The document-free companion the Organization listing uses must agree with the full query about which version
    // is newest. It is a separate SQL statement (MAX + GROUP BY, against latestBriefs's DISTINCT ON), so agreeing
    // is a property that has to be asserted rather than assumed.
    assertEquals(briefs.findLatestVersions().get(organization.id()).intValue(), 2);

    // Deleting is an assertion here, not cleanup -- BaseTest empties the database between methods regardless. What
    // it proves is the ON DELETE CASCADE that the whole test suite's isolation depends on: removing the
    // Organization has to take its source and every Brief version with it.
    sources.create(new BriefSource(UUID.randomUUID(), organization.id(), new GitHubConfig(null, "Acme", "briefs",
        "main"), null, null, null, null, now, now));
    organizations.delete(organization.id());
    assertTrue(organizations.findById(organization.id()).isEmpty());
    assertTrue(sources.findByOrganizationId(organization.id()).isEmpty());
    assertTrue(briefs.findLatestByOrganizationId(organization.id()).isEmpty());
  }
}
