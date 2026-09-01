/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * What the Briefing API should tell a Handler. Sealed so no caller can forget a case.
 */
public sealed interface BriefingOutcome {
  /**
   * Every version and checksum the Handler asserted is current, and its entitled set is unchanged. Answer 304.
   */
  record NotModified() implements BriefingOutcome {
  }

  /**
   * Answer 200. {@code organizationIds} is the complete entitled set — not a delta — because any Organization the
   * Handler holds but which is absent from it is treated as revoked and torn down. {@code briefs} carries only the
   * Briefs that are stale.
   */
  record Updated(List<String> organizationIds, List<Brief> briefs) implements BriefingOutcome {
  }
}
