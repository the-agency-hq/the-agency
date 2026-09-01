/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Narrows a Brief to the files the Organization's selected Agents read, on the way out to a Handler.
 *
 * <p>The stored Brief always carries every Translator's output: the selection is a serving decision, not a build
 * one, so widening it later serves files that were built all along rather than waiting on a rebuild. Whether an
 * Agent reads a file is each {@link dev.theagencyhq.agency.service.translation.Translator}'s answer — a file can be
 * read by several Agents ({@code .agents/skills} by nearly all of them) and stays as long as any selected one
 * reads it. An Organization that has not narrowed its selection gets the Brief untouched.
 */
public final class BriefReducer {
  private BriefReducer() {
  }

  /**
   * @param brief A stored Brief, whose Organization carries the selection it was published under.
   * @return The same Brief with only the files the selected Agents read, or the Brief itself when the selection
   *     is every Agent. Version, checksum and provenance are untouched: the reduction is not a new version, and the
   *     Handler's assertions are compared against what was stored.
   */
  public static Brief reduce(Brief brief) {
    var organization = brief.organization();
    if (organization.agents() == null) {
      return brief;
    }

    var readers = BriefBuilder.TRANSLATORS.stream()
                                          .filter(t -> t.agent() != null && organization.serves(t.agent()))
                                          .toList();
    var files = brief.files()
                     .stream()
                     .filter(f -> readers.stream().anyMatch(t -> t.reads(f.path())))
                     .toList();
    return new Brief(brief.checksum(), organization, brief.version(), files, brief.sourceCommit(),
        brief.insertInstant());
  }
}
