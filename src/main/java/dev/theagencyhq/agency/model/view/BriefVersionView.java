/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * One Brief version's detail page, parsed from the stored {@code document} rather than rebuilt from the source
 * tree, so it shows exactly what was published.
 *
 * @param agents The Agent selection the version was published under, or {@code null} for every Agent. Off the
 *               stored document, not the Organization's row: a later change to the selection publishes a later
 *               version, and this page describes this one.
 */
public record BriefVersionView(Organization organization, int version, String checksum, String sourceCommit,
                               Instant insertInstant, Agents agents, List<Entry> entries) {
  public record Entry(int index, String path, String encoding, String mode, int size, List<String> missionTypes) {
  }
}
