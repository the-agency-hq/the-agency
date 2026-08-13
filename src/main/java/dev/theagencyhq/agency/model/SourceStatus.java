/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * The outcome of the most recent poll cycle for a Brief source.
 *
 * <p>The three failures are kept apart because each is a different person's problem. {@link #NOT_CONNECTED} is the
 * Agency operator's — the GitHub authorization behind this source has lapsed and has to be granted again. {@link
 * #FETCH_FAILED} is usually nobody's, and clears on its own on the next cycle. {@link #BUILD_FAILED} belongs to
 * whoever wrote the last commit to the source repository. Collapsing them into one status would leave the admin UI
 * telling all three the same thing.
 */
public enum SourceStatus {
  BUILD_FAILED,
  FETCH_FAILED,
  NOT_CONNECTED,
  OK,
  UNCHANGED
}
