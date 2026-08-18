/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The authenticated caller, translated from the FusionAuth access token bound to the request. Not a database row:
 * the Agency stores no users, and every field here comes from a claim, so the record is only ever as current as the
 * token that carried it.
 *
 * <p>{@code userId} is the only member anything should key on. It is FusionAuth's user id, which is stable across
 * an email change, and it is what the Organization memberships will hang off when entitlements arrive
 * (2026-07-30 design §10.4). {@code email} is display text.
 *
 * @param userId The FusionAuth user id, from the {@code sub} claim.
 * @param email  The user's email address, from the {@code email} claim. Null when the provider omits it — the claim
 *               is populated by the JWT-populate lambda in {@code src/main/fusionauth}, not by FusionAuth's
 *               defaults, so a provider configured without it still authenticates.
 */
public record User(UUID userId, String email) {
}
