/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.api;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.agency.model.api.internal.*;

/**
 * The body of {@code POST /api/v1/briefing}.
 */
@JSON
public record BriefingRequest(List<CurrentVersion> currentVersions) {
  public BriefingRequest {
    currentVersions = currentVersions == null ? List.of() : List.copyOf(currentVersions);
  }

  public static BriefingRequest fromJSON(byte[] bytes) {
    return BriefingRequestJSON.fromJSON(bytes);
  }
}
