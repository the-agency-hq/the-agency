/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * An Organization: the whole {@code organizations} row, both in the database and on the wire inside a Brief. The
 * name is display text: it is stored as the author typed it, case and all, and nothing derives an identifier,
 * path or URL from it — the {@code id} is what everything keys on. Uniqueness is still case-insensitive and
 * first-come-first-serve per idea.md, but that is enforced by the {@code LOWER(name)} unique index rather than by
 * flattening the stored value. {@code agents} is the {@link Agents} selection the Organization narrowed to, or
 * {@code null} for all of them — the default, and the state every Organization starts in. {@code gitHubConnection}
 * is the GitHub authorization granted to the Organization, nested rather than flattened, and {@code null} for as
 * long as it is not connected.
 *
 * <p>One type rather than a domain record and a wire twin. Inside a Brief only {@code id}, {@code name} and
 * {@code agents} travel: {@code BriefBuilder} nulls the rest, which serialization then omits — the connection
 * because it is a live bearer credential and a Brief document is served to every Handler in the fleet, the
 * instants because {@code update_instant} moves on every GitHub token refresh and would poison the Brief's content
 * checksum. {@code agents} does travel, deliberately: the selection decides which of the Brief's files a Handler
 * is served, so a Brief published under one selection must checksum differently from the same files published
 * under another. A {@code null} selection is omitted from the JSON, so a Brief for an Organization that wants
 * every Agent checksums exactly as it did before selections existed.
 * {@code id} is a {@link UUID} here and a String there — a UUID serializes as a JSON string, and it satisfies the
 * Handler's requirement that the id be a single path segment, since it resolves the id against its store root.
 */
@JSON
public record Organization(UUID id, String name, Agents agents,
                           @JSONField(ignore = true) GitHubConnection gitHubConnection, Instant insertInstant,
                           Instant updateInstant) {
  public Organization {
    name = name == null ? null : name.trim();

    // Truncated to the precision the database actually stores (both instant columns are BIGINT epoch millis), and
    // Instant.now() carries sub-millisecond digits on Linux -- without this, an Organization built in memory and
    // the same one read back from the database compare unequal despite being the same Organization.
    insertInstant = insertInstant == null ? null : insertInstant.truncatedTo(ChronoUnit.MILLIS);
    updateInstant = updateInstant == null ? null : updateInstant.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * @param agent An Agent.
   * @return True if the Organization is interested in the Agent — always, when it has not narrowed the selection.
   */
  public boolean serves(Agent agent) {
    return agents == null || agents.serves(agent);
  }
}
