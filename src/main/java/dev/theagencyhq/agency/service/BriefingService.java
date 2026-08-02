/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.db.*;
import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.api.*;

/**
 * Decides what a Handler is told. A pure function over what the Handler asserted and what the database holds — no I/O,
 * no state — so the whole §10.2 matrix is testable without a database or a socket.
 */
public class BriefingService {
  private final DatabaseService databaseService;

  public BriefingService(DatabaseService databaseService) {
    this.databaseService = databaseService;
  }

  /**
   * @param request The API request.
   * @return The outcome.
   */
  public BriefingOutcome decide(BriefingRequest request) {
    var organizations = databaseService.listOrganizations();
    var latestBriefs = databaseService.latestBriefs();

    // One canonical ordering for both output arrays, keyed on the id's String form. The wire carries strings and the
    // Handler compares strings, so that is the form the ordering has to agree with. Sorting once and deriving
    // `deliverable` from the already-sorted list is what keeps them identical: sorting the same ids by UUID's own
    // natural order (signed mostSigBits) as well would give two orderings that disagree whenever a leading hex
    // nibble is >= 8, which is a trap for anyone who later pairs the arrays up.
    var entitledIds = organizations.stream().map(Organization::id).sorted(Comparator.comparing(UUID::toString)).toList();

    // Deliverable = entitled AND has at least one built Brief. The distinction matters twice below. A filter of an
    // ordered list, so it inherits the ordering above rather than establishing a second one.
    var deliverable = entitledIds.stream().filter(latestBriefs::containsKey).toList();

    // hasUnparseableId tracks a malformed assertion with a plain flag rather than a sentinel Organization id (e.g.
    // the nil UUID) inserted into the same set as real ids: a sentinel value shares the domain of real ids and
    // could in principle collide with one, however improbable, whereas a flag cannot collide with anything.
    var assertedByOrganization = new HashMap<UUID, CurrentVersion>();
    var hasUnparseableId = false;
    var asserted = request.currentVersions();
    for (var current : asserted) {
      // An unparseable id cannot name an Organization we know about. Treating it as unknown rather than throwing
      // keeps a malformed assertion from failing the whole request; hasUnparseableId then forces the set
      // comparison below to fail, so a malformed entry can never be mistaken for the steady state.
      UUID id;
      try {
        id = UUID.fromString(current.organizationId());
      } catch (IllegalArgumentException _) {
        hasUnparseableId = true;
        continue;
      }

      assertedByOrganization.put(id, current);
    }

    var stale = new ArrayList<Brief>();
    for (var id : deliverable) {
      var brief = latestBriefs.get(id);
      var current = assertedByOrganization.get(id);
      if (current == null || current.version() != brief.version() || !current.checksum().equals(brief.checksum())) {
        stale.add(brief);
      }
    }

    // The set comparison is what makes revocation self-healing: without it, an Organization deleted from the
    // Agency while nothing else changed would 304 forever and the Handler would keep serving its Brief. The
    // !deliverable.isEmpty() guard closes a vacuous-truth case: an empty assertion trivially equals an empty
    // deliverable set, but that never proves the Handler is caught up -- it is equally what a brand-new Handler
    // looks like before anything has ever been built, and that Handler still needs organizationIds so it learns
    // its entitled set instead of being told, uninformatively, that nothing changed.
    if (!deliverable.isEmpty() && stale.isEmpty() && !hasUnparseableId &&
        assertedByOrganization.keySet().equals(new HashSet<>(deliverable))) {
      return new BriefingOutcome.NotModified();
    }

    // organizationIds is `entitled`, not `deliverable`: a registered-but-unbuilt Organization is still one the
    // Handler is entitled to, and omitting it would make the Handler tear that Location down.
    return new BriefingOutcome.Updated(
        entitledIds.stream().map(UUID::toString).toList(),
        List.copyOf(stale));
  }
}
