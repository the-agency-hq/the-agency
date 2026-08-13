/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.github.GitHubClient;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.service.validation.OrganizationValidator;
import dev.theagencyhq.agency.service.validation.SourceValidator;

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

  public Organization create(String name) {
    OrganizationValidator.validate(name, database);

    var now = Instant.now();
    var organization = new Organization(UUID.randomUUID(), name, null, now, now);
    database.insertOrganization(organization);
    return organization;
  }

  public void delete(UUID organizationId) {
    database.deleteOrganization(organizationId);
  }
}
