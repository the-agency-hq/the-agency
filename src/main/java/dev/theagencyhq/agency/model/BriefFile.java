/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;
import module org.lattejava.json;

/**
 * One file in a Brief. The {@code checksum} is SHA-256 of the decoded bytes, hex-encoded lowercase — the Handler
 * verifies it and refuses to store a Brief that fails, so an incorrect value silently stalls the whole fleet.
 * The {@code mode} is symbolic: exactly nine {@code rwx-} characters in {@code ls -l} order, which the Handler
 * feeds straight to {@code PosixFilePermissions.fromString}. An octal mode fails there, so it stalls the fleet
 * the same way a bad checksum does.
 * <p>
 * Like {@link Brief}, a file canonicalizes itself, because everything here feeds the Brief checksum: without it,
 * an edit that changes nothing a consumer can observe still republishes the Brief and re-downloads it on every
 * machine in the fleet.
 */
@JSON
public record BriefFile(String path, String encoding, String mode, String content, String checksum,
                        List<String> missionTypes) {
  public static final String DEFAULT_ENCODING = "text";
  public static final String DEFAULT_MODE = "r--------";
  public static final String ENCODING_BASE64 = "base64";
  public static final String EXECUTABLE_MODE = "r-x------";

  public BriefFile {
    path = path == null ? "" : path.trim();
    encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING : encoding.trim().toLowerCase(Locale.ROOT);
    mode = mode == null || mode.isBlank() ? DEFAULT_MODE : mode.trim();
    content = content == null ? "" : content;
    checksum = checksum == null ? "" : checksum.trim().toLowerCase(Locale.ROOT);
    // Trimmed, lowercased, deduplicated, sorted. Reordering or recasing a `.mission-types` file changes nothing
    // a consumer can observe -- the Handler lowercases both sides before comparing -- so none of it may reach the
    // checksum. Lowercasing here is exactly what the Handler does on parse, so the two agree by construction
    // rather than by both sides remembering to.
    missionTypes = missionTypes == null ? List.of()
        : missionTypes.stream()
                      .filter(Objects::nonNull)
                      .map(t -> t.trim().toLowerCase(Locale.ROOT))
                      .filter(t -> !t.isEmpty())
                      .distinct()
                      .sorted()
                      .toList();
  }
}
