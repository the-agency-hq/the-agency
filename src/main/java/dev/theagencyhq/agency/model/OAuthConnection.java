/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module org.lattejava.json;

/**
 * A repository source's authorization: the {@code connection} member of its {@link BriefSourceConfig}, and the one
 * member of it that the OAuth callback and the token refresh write.
 *
 * <p>It holds a live bearer credential, and it serializes for exactly one destination: the
 * {@code brief_sources.source_config} document. Nothing on the wire carries a source's configuration — a Brief
 * embeds its Organization's identity and selection, never its source — so the credential never leaves the
 * database except into the poller.
 *
 * @param login  The account the authorization was granted as — a GitHub login, a GitLab or Bitbucket username.
 *               Display text only; it is as current as the last connection.
 * @param tokens The credential the poller works with.
 */
@JSON
public record OAuthConnection(String login, OAuthTokens tokens) {
}
