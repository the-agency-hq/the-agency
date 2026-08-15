/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * A membership row's lifecycle: PENDING from the moment someone is invited, ACTIVE once they accept. Declining or
 * cancelling an invitation deletes the row, so there is no third state.
 */
public enum MembershipState {
  ACTIVE,
  PENDING
}
