/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.api;

import module java.base;
import module org.lattejava.json;

/**
 * One entry in a Handler's assertion of what it currently holds.
 */
@JSON
public record CurrentVersion(String organizationId, int version, String checksum) {
  public CurrentVersion {
    organizationId = organizationId == null ? "" : organizationId.trim();
    checksum = checksum == null ? "" : checksum.trim();
  }
}
