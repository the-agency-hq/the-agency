/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module org.lattejava.json;

import dev.theagencyhq.agency.model.*;

/**
 * An Organization's GitHub authorization, nested on {@link Organization} exactly as its columns sit on the
 * {@code organizations} table.
 *
 * <p>It holds a live bearer credential, so although it serializes — its codec exists because Organization's does —
 * it must never actually travel: {@code BriefBuilder} nulls it, along with the instants, before an Organization is
 * embedded in a Brief, and a Brief document is the only place an Organization is ever serialized.
 *
 * @param login  The GitHub login the authorization was granted as. Display text only — it is as current as the
 *               last connection.
 * @param tokens The credential the poller works with.
 */
@JSON
public record GitHubConnection(String login, GitHubTokens tokens) {
}
