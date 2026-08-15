/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.api;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.api.internal.OrganizationsResponseJSON;

/**
 * The response body of {@code GET /api/v1/organization}: the Organizations the caller has access to. An envelope
 * rather than a bare array, like {@link BriefingResponse}, so a member can be added later without changing the
 * shape of the document a caller already parses.
 */
@JSON
public record OrganizationsResponse(List<Organization> organizations) {
  public OrganizationsResponse {
    organizations = organizations == null ? List.of() : organizations;
  }

  public static OrganizationsResponse fromJSON(byte[] json) {
    return OrganizationsResponseJSON.fromJSON(json);
  }
}
