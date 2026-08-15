/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The invite form's submission: who to invite to which Organization, and as what. The email is folded to lowercase
 * here so every comparison and FusionAuth lookup downstream sees one canonical form.
 */
public record InviteRequest(UUID organizationId, String email, Role role) {
  public InviteRequest {
    email = email == null ? null : email.toLowerCase(Locale.ROOT).trim();
  }
}
