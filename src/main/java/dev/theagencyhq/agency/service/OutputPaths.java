/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * Validates the Brief output paths the Translators produce.
 *
 * <p>The rules mirror the Handler's planner exactly. A Brief that violates any of them makes the Handler reject
 * the entire plan for that Location, so publishing one would silently stop the Organization updating on every
 * machine in the fleet.
 */
public final class OutputPaths {
  public static final String GITIGNORE_NAME = ".gitignore";
  public static final String MANIFEST_NAME = ".handler-manifest";
  public static final String TEMP_INFIX = ".handler-tmp-";

  private OutputPaths() {
  }

  public static void validate(String outputPath) {
    if (outputPath.isEmpty()) {
      throw new BriefBuildException("A Brief file path is empty");
    }

    // Checked before splitting so a NUL byte surfaces as this failure rather than an InvalidPathException. A
    // newline is the dangerous one: the Handler's manifest and git-exclude writers are both line-oriented and
    // neither escapes, so an embedded newline injects a standalone line into the Handler's own bookkeeping.
    for (int i = 0; i < outputPath.length(); i++) {
      char c = outputPath.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] contains a control character");
      }
    }

    if (outputPath.startsWith("/")) {
      throw new BriefBuildException("Brief file path [" + outputPath + "] is absolute");
    }

    for (var segment : outputPath.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] has an empty or relative segment");
      }

      // Lowercased, and every segment rather than only the first. macOS APFS is case-insensitive by default, so
      // `.GIT/hooks/pre-commit` IS `.git/hooks/pre-commit`, and a fabricated repository anywhere in the tree gives
      // git a repo-local core.pager / core.fsmonitor / alias.* that executes on the next git invocation.
      var lower = segment.toLowerCase(Locale.ROOT);
      if (lower.equals(".git")) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] contains a [.git] segment");
      }
      if (lower.equals(MANIFEST_NAME)) {
        throw new BriefBuildException(
            "Brief file path [" + outputPath + "] contains a [" + MANIFEST_NAME + "] segment");
      }
      // The Handler creates and maintains .gitignore itself as part of its own bootstrap, so a Brief that names one
      // collides with a file the Handler just wrote and its planner rejects the entire Location's plan. Every
      // Handler still downloads the Brief, verifies it, commits it to its store, and reports the new version as
      // current before that rejection happens, so the fleet stalls silently with nothing anywhere reporting a
      // failure. Rejecting it here is the only place the failure is attributable to the file that caused it.
      if (lower.equals(GITIGNORE_NAME)) {
        throw new BriefBuildException(
            "Brief file path [" + outputPath + "] contains a [" + GITIGNORE_NAME + "] segment");
      }
      if (lower.contains(TEMP_INFIX)) {
        throw new BriefBuildException(
            "Brief file path [" + outputPath + "] contains the reserved infix [" + TEMP_INFIX + "]");
      }
    }
  }
}
