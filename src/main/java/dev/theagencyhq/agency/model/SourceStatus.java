/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * The outcome of the most recent poll cycle for a Brief source.
 */
public enum SourceStatus {
  BUILD_FAILED,
  NOT_A_REPOSITORY,
  OK,
  UNCHANGED
}
