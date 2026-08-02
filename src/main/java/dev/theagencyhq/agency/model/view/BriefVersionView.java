/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.Organization;

/**
 * One Brief version's detail page, parsed from the stored {@code document} rather than rebuilt from the source
 * tree, so it shows exactly what was published.
 */
public record BriefVersionView(Organization organization, int version, String checksum, String sourceCommit,
                               Instant insertInstant, List<Entry> entries) {
  public record Entry(int index, String path, String encoding, String mode, int size, List<String> missionTypes) {
  }
}
