/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.api;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.agency.model.api.internal.BriefingResponseJSON;

/**
 * The response body of {@code POST /api/v1/briefing}.
 */
@JSON
public record BriefingResponse(List<String> organizationIds, List<Brief> briefs) {
  public BriefingResponse {
    organizationIds = organizationIds == null ? List.of() : organizationIds.stream().map(String::trim).toList();
    briefs = briefs == null ? List.of() : briefs;
  }

  public static BriefingResponse fromJSON(byte[] json) {
    return BriefingResponseJSON.fromJSON(json);
  }
}
