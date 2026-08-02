/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.service.validation.OrganizationValidator;

/**
 * Creates and deletes Organizations along with their single Brief source.
 */
public class OrganizationService {
  private final DatabaseService database;
  private final GitService git;

  public OrganizationService(DatabaseService database, GitService git) {
    this.database = database;
    this.git = git;
  }

  public Organization create(String name, String path) {
    OrganizationValidator.validate(name, path, database, git);

    var now = Instant.now();
    var organization = new Organization(UUID.randomUUID(), name, now, now);
    var source = new BriefSource(UUID.randomUUID(), organization.id(), path.trim(), null, null, null, null, null,
        now, now);
    database.createOrganizationWithSource(organization, source);
    return organization;
  }

  public void delete(UUID organizationId) {
    database.deleteOrganization(organizationId);
  }
}
