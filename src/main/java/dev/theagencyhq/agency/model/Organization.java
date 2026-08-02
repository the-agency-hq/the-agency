/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;
import module org.lattejava.json;

/**
 * An Organization, both in the database and on the wire inside a Brief. The name is display text: it is stored as
 * the author typed it, case and all, and nothing derives an identifier, path or URL from it — the {@code id} is
 * what everything keys on. Uniqueness is still case-insensitive and first-come-first-serve per idea.md, but that
 * is enforced by the {@code LOWER(name)} unique index rather than by flattening the stored value.
 *
 * <p>One type rather than a domain record and a wire twin. The Handler reads only {@code id} and {@code name} and
 * parses non-strictly, so the timestamps ride along as unknown keys it ignores. {@code id} is a {@link UUID} here
 * and a String there — a UUID serializes as a JSON string, and it satisfies the Handler's requirement that the id
 * be a single path segment, since it resolves the id against its store root.
 */
@JSON
public record Organization(UUID id, String name, Instant insertInstant, Instant updateInstant) {
  public Organization {
    name = name == null ? null : name.trim();

    // Truncated to the precision the database actually stores (both instant columns are BIGINT epoch millis).
    // These ride inside the Brief and so feed its checksum, and Instant.now() carries sub-millisecond digits on
    // Linux -- without this, a Brief built against a freshly constructed Organization and one built against the
    // same Organization read back from the database hash differently despite being the same Organization.
    insertInstant = insertInstant == null ? null : insertInstant.truncatedTo(ChronoUnit.MILLIS);
    updateInstant = updateInstant == null ? null : updateInstant.truncatedTo(ChronoUnit.MILLIS);
  }
}
