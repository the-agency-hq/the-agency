/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Turns the source tree into the Brief files one family of Agents reads. Every Translator sees the whole tree and
 * decides for itself what to take from it, so a concept that one Agent stores as a directory of files and another
 * stores as one concatenated document is each Translator's own business rather than a shared path table's.
 *
 * <p>Pure: a Translator touches no database, no network and no filesystem, and it must be deterministic — the same
 * tree always produces byte-identical files, because those bytes feed the Brief checksum and a spurious difference
 * publishes a version every Handler in the fleet then downloads.
 *
 * <p>Each output path belongs to exactly one Translator. {@code BriefBuilder} fails the build when two produce the
 * same path, so two Translators can never quietly disagree about one file's content.
 */
public interface Translator {
  /**
   * @param source The source tree.
   * @return The Brief files this Translator produces from it. May be empty.
   */
  List<BriefFile> translate(SourceTree source);
}
