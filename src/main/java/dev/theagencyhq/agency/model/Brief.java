/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.agency.model.internal.*;

/**
 * A Brief as the Handler receives it. Member declaration order is the wire key order and matches the Handler's own
 * record.
 * <p>
 * {@code checksum} and {@code version} are nullable, and that is what lets this one record be both the published Brief
 * and its own checksum input. {@code @JSON} omits null members, so a Brief carrying neither serializes to just
 * {@code organization} and {@code files} — the content, with the two members that must not feed the checksum absent
 * rather than merely blank. A published Brief always carries both, so the wire shape the Handler sees is unchanged.
 * <p>
 * A Brief canonicalizes itself: {@code files} is always sorted by path. The checksum is a hash of this record's JSON,
 * and the serializer writes a list in iteration order, so two Briefs with identical content but different file order
 * would otherwise hash differently and publish a new version for content that did not change. Owning that here rather
 * than in the builder means it holds for every Brief however it was constructed — rebuilt from a working tree, parsed
 * back off the wire, or copied to attach a version.
 * <p>
 * {@code sourceCommit} and {@code insertInstant} are the version's provenance, assigned by the insert and read back off
 * the {@code briefs} row rather than out of the stored document. They are declared last so the members the Handler
 * reads keep the exact prefix its own record declares, and they are null on a Brief that has not been stored yet —
 * which is why they never reach the checksum.
 */
@JSON
public record Brief(String checksum, Organization organization, Integer version, List<BriefFile> files,
                    String sourceCommit, Instant insertInstant) {
  public Brief {
    // Deliberately null rather than "": the empty string is a value, and a value would be written to the wire,
    // which would put the checksum member inside its own input.
    checksum = checksum == null || checksum.isBlank() ? null : checksum.trim();
    sourceCommit = sourceCommit == null || sourceCommit.isBlank() ? null : sourceCommit.trim();

    // Truncated for the same reason Organization's are: the column is BIGINT epoch millis.
    insertInstant = insertInstant == null ? null : insertInstant.truncatedTo(ChronoUnit.MILLIS);

    // Paths are unique within a Brief -- the builder fails a tree that maps two sources onto one output path -- so
    // sorting by path is a total order and there is no tie for a stable sort to resolve arbitrarily.
    var canonical = files == null ? new ArrayList<BriefFile>() : new ArrayList<>(files);
    canonical.sort(Comparator.comparing(BriefFile::path));
    files = List.copyOf(canonical);
  }

  public static Brief fromJSON(String json) {
    return BriefJSON.fromJSON(json);
  }

  public BriefFile fileAt(String path) {
    return files.stream()
                .filter(f -> f.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Brief file at [" + path + "] in [" + files.stream().map(BriefFile::path).toList() + "]"));
  }

  public String toJSON() {
    return BriefJSON.toJSON(this);
  }
}
