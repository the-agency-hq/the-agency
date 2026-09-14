/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;

import dev.theagencyhq.agency.model.Member;

/**
 * Creates and deletes Organizations, and registers the repository an Organization's Briefs are built from.
 *
 * <p>Creating and connecting are two calls because they are two steps for the operator: an Organization is named
 * first and exists from that moment, with no source, and is pointed at a repository afterwards — which cannot
 * happen until an authorization with the host exists to list repositories with, and that authorization is what
 * creates the source row. An Organization with no source, or with a source that is connected and not yet registered, is
 * therefore an ordinary state rather than a broken one; the poller has nothing to poll for it and the admin UI says
 * so.
 */
@Prototype
public class OrganizationService {
  private final BriefRepository briefs;
  private final SourceCatalog catalog;
  private final Database database;
  private final MemberRepository members;
  private final OrganizationRepository organizations;
  private final BriefSourceRepository sourceRepository;

  public OrganizationService(Database database, BriefRepository briefs, BriefSourceRepository sourceRepository,
                             MemberRepository members, OrganizationRepository organizations, SourceCatalog catalog) {
    this.briefs = briefs;
    this.catalog = catalog;
    this.database = database;
    this.members = members;
    this.organizations = organizations;
    this.sourceRepository = sourceRepository;
  }

  /**
   * Points an Organization's source at a repository, replacing whatever repository it polled before and keeping the
   * authorization that polls it.
   *
   * <p>The poll history is deliberately not carried over. {@code lastBuiltCommit} in particular belongs to whatever
   * repository was registered before, and preserving it across a change of repository would make the next cycle
   * compare the new repository's head against the old one's and, if they happened to agree, skip the build that
   * was the entire point of reconnecting.
   *
   * @param type           The kind of source the picker was rendered for.
   * @param organizationId The Organization to connect.
   * @param accessToken    The Organization's token for the host, used to verify the repository before it is
   *                       registered.
   * @param fullName       The repository as the host names it.
   * @param branch         The branch to build from.
   * @return The registered source, or {@code null} if the Organization no longer has a source of that kind to
   *     register it on — its authorization was removed, or replaced by another kind's, between the caller resolving
   *     a token and this call.
   * @throws dev.theagencyhq.agency.error.ValidationException if the repository is not a usable Brief source, or is
   *     already registered to another Organization.
   */
  public BriefSource connect(BriefSourceType type, UUID organizationId, String accessToken, String fullName,
                             String branch) {
    SourceValidator.validate(type, organizationId, fullName, branch, accessToken, sourceRepository,
        catalog.client(type));

    var existing = sourceRepository.findByOrganizationId(organizationId).orElse(null);
    if (existing == null || existing.type() != type) {
      return null;
    }

    var now = Instant.now();
    var registered = new BriefSource(existing.id(), organizationId,
        existing.config().withRepository(fullName.trim(), branch), null, null, null, null, existing.insertInstant(),
        now);
    return sourceRepository.update(registered) ? registered : null;
  }

  /**
   * Names a new Organization and makes its creator the first member: an ACTIVE OWNER with no inviter, exactly as
   * {@code latte-java/app} seats a group's creator — because an Organization without an ACTIVE OWNER is one nobody
   * can administer.
   *
   * @param name    The Organization's display name.
   * @param creator The signed-in user creating it.
   * @return The created Organization.
   * @throws dev.theagencyhq.agency.error.ValidationException if the name is missing, too long, or taken.
   */
  public Organization create(String name, User creator) {
    OrganizationValidator.validate(name, organizations);

    var now = Instant.now();
    var organization = new Organization(UUID.randomUUID(), name, null, now, now);
    // One transaction: an Organization without its first Owner is one nobody can administer, so the two rows land
    // together or not at all.
    database.transaction(() -> {
      organizations.create(organization);
      members.create(
          new Member(organization.id(), creator.userId(), Role.OWNER, MembershipState.ACTIVE, null, null, now));
    });
    return organization;
  }

  /**
   * Deletes an Organization on the operator's say-so. Its memberships, its source — credential included — and every
   * version of its Brief go with it, by the schema's cascades, and every Handler polling it is served nothing for it
   * from its next poll on.
   *
   * @param organization The Organization to delete.
   * @param confirmation The Organization's name, as the operator typed it.
   * @throws ValidationException if the confirmation is not the Organization's name.
   */
  public void delete(Organization organization, String confirmation) {
    OrganizationValidator.validateDelete(organization, confirmation);
    delete(organization.id());
  }

  /**
   * Deletes an Organization outright, cascades and all, asking nothing. The admin UI goes through
   * {@link #delete(Organization, String)}; this is for a caller that already holds the decision.
   *
   * @param organizationId The Organization.
   */
  public void delete(UUID organizationId) {
    organizations.delete(organizationId);
  }

  /**
   * Changes which Agents an Organization is interested in. A change republishes the latest Brief as a new version
   * carrying the new selection, so every Handler picks it up on its next poll: the version and checksum they echo
   * no longer match, and the Brief they are then served is reduced to the new selection. Nothing is rebuilt — the
   * files are the latest version's, verbatim — and an Organization with no version yet gets no version, since its
   * first build embeds the selection anyway.
   *
   * @param organization The Organization as currently stored.
   * @param agents       The new selection, or {@code null} for every Agent.
   * @return True if the selection changed. An unchanged selection writes nothing and publishes nothing.
   * @throws ValidationException if the selection names no Agent at all.
   */
  public boolean updateAgents(Organization organization, Agents agents) {
    if (agents != null && (agents.enabled() == null || agents.enabled().isEmpty())) {
      throw new ValidationException(List.of("Select at least one Agent, or All."));
    }

    // The Agents record canonicalizes (sorts, deduplicates) at construction, so both sides compare as selections
    // rather than as orderings.
    var now = Instant.now();
    var updated = new Organization(organization.id(), organization.name(), agents, organization.insertInstant(),
        now);
    if (Objects.equals(updated.agents(), organization.agents())) {
      return false;
    }

    // The republished document is built exactly as BriefBuilder builds one -- identity and selection only, then
    // checksummed -- so the next poll, which builds the same files under the same selection, computes the same
    // checksum and records UNCHANGED rather than publishing a duplicate.
    var republished = briefs.findLatestByOrganizationId(organization.id()).map(latest -> {
      var content = new Brief(null, new Organization(organization.id(), organization.name(), updated.agents(), null,
          null), null, latest.files(), null, null);
      return new Brief(BriefBuilder.checksum(content), content.organization(), null, content.files(),
          latest.sourceCommit(), now);
    }).orElse(null);

    // One transaction, so the row can never say one thing while the latest version says another.
    database.transaction(() -> {
      organizations.update(updated);
      if (republished != null) {
        briefs.create(republished);
      }
    });
    return true;
  }
}
