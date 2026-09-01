/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module java.base;

import dev.theagencyhq.agency.model.Member;

/**
 * Creates and deletes Organizations, and connects one to the GitHub repository its Briefs are built from.
 *
 * <p>Creating and connecting are two calls because they are two steps for the operator: an Organization is named
 * first and exists from that moment, with no source, and is pointed at a repository afterwards — which cannot
 * happen until a GitHub authorization exists to list repositories with. An Organization with no source is therefore
 * an ordinary state rather than a broken one; the poller has nothing to poll for it and the admin UI says so.
 */
public class OrganizationService {
  private final DatabaseService database;
  private final GitHubClient github;

  public OrganizationService(DatabaseService database, GitHubClient github) {
    this.database = database;
    this.github = github;
  }

  /**
   * Points an Organization at a GitHub repository, replacing whatever source it had.
   *
   * @param organizationId The Organization to connect.
   * @param accessToken    The Organization's GitHub token, used to verify the repository before it is registered.
   * @param owner          The repository owner.
   * @param repository     The repository name.
   * @param branch         The branch to build from.
   * @return The registered source.
   * @throws dev.theagencyhq.agency.error.ValidationException if the repository is not a usable Brief source.
   */
  public BriefSource connect(UUID organizationId, String accessToken, String owner, String repository,
                             String branch) {
    SourceValidator.validate(owner, repository, branch, accessToken, database, github);

    var now = Instant.now();
    var source = new BriefSource(UUID.randomUUID(), organizationId, owner, repository, branch, null, null, null,
        null, now, now);
    database.replaceSource(source);
    return source;
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
    OrganizationValidator.validate(name, database);

    var now = Instant.now();
    var organization = new Organization(UUID.randomUUID(), name, null, null, now, now);
    database.insertOrganization(organization);
    database.insertMember(
        new Member(organization.id(), creator.userId(), Role.OWNER, MembershipState.ACTIVE, null, null, now));
    return organization;
  }

  public void delete(UUID organizationId) {
    database.deleteOrganization(organizationId);
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
    var updated = new Organization(organization.id(), organization.name(), agents, organization.gitHubConnection(),
        organization.insertInstant(), now);
    if (Objects.equals(updated.agents(), organization.agents())) {
      return false;
    }

    // The republished document is built exactly as BriefBuilder builds one -- identity and selection only, then
    // checksummed -- so the next poll, which builds the same files under the same selection, computes the same
    // checksum and records UNCHANGED rather than publishing a duplicate.
    var republished = database.findLatestBrief(organization.id()).map(latest -> {
      var content = new Brief(null, new Organization(organization.id(), organization.name(), updated.agents(), null,
          null, null), null, latest.files(), null, null);
      return new Brief(BriefBuilder.checksum(content), content.organization(), null, content.files(),
          latest.sourceCommit(), now);
    }).orElse(null);
    database.updateAgents(organization.id(), updated.agents(), now, republished);
    return true;
  }
}
