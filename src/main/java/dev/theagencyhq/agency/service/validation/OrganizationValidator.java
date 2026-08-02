/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.validation;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.service.BriefBuildException;
import dev.theagencyhq.agency.service.BriefBuilder;
import dev.theagencyhq.agency.service.GitService;

/**
 * Validates a new Organization and its source Path. The name is display text and carries no character-set
 * restriction: nothing derives an identifier, path or URL from it, so there is nothing for a restricted alphabet
 * to protect. It has to be present, has to fit {@link #NAME_MAX_LENGTH}, and has to be unique.
 */
public final class OrganizationValidator {
  public static final int NAME_MAX_LENGTH = 255;

  private OrganizationValidator() {
  }

  public static void validate(String name, String path, DatabaseService database, GitService git) {
    var errors = new ArrayList<String>();

    // Trimmed but not lowercased: the stored name keeps the author's case, and the uniqueness check below is
    // case-insensitive on the database's terms rather than on this method's.
    var trimmedName = name == null ? "" : name.trim();
    if (trimmedName.isEmpty()) {
      errors.add("A name is required.");
    } else if (trimmedName.length() > NAME_MAX_LENGTH) {
      errors.add("The name must be at most " + NAME_MAX_LENGTH + " characters, but was [" + trimmedName.length()
                 + "].");
    } else if (database.findOrganizationByName(trimmedName).isPresent()) {
      errors.add("The name [" + trimmedName + "] is already registered.");
    }

    var trimmed = path == null ? "" : path.trim();
    if (trimmed.isEmpty()) {
      errors.add("A source path is required.");
    } else {
      var error = pathError(trimmed, database, git);
      if (error != null) {
        errors.add(error);
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  /**
   * @param path     The trimmed source path as the operator typed it.
   * @param database The database, for the uniqueness check.
   * @param git      The Git service, for the work-tree check.
   * @return The first reason this path cannot be registered, or {@code null} if it can. Only the first, because the
   *     later checks presuppose the earlier ones — asking Git about a path that is not a directory, or parsing a
   *     settings file in a directory that is not a repository, produces noise rather than a second useful error.
   */
  private static String pathError(String path, DatabaseService database, GitService git) {
    Path resolved;
    try {
      resolved = Path.of(path);
    } catch (InvalidPathException _) {
      return "The path [" + path + "] is not a valid path.";
    }

    if (!resolved.isAbsolute()) {
      return "The path [" + path + "] must be absolute.";
    }
    if (!Files.isDirectory(resolved)) {
      return "The path [" + path + "] is not an existing directory.";
    }
    if (!git.isWorkTree(resolved)) {
      return "The path [" + path + "] is not a Git repository.";
    }

    // The settings marker is PARSED here, not merely looked for. An existence check accepts a repository whose
    // layout version this Agency does not support, or whose marker is not valid JSON at all; that repository then
    // registers cleanly and fails on every poll cycle from then on, with the only evidence a BUILD_FAILED status on
    // a detail page nobody has a reason to open yet. Calling BriefBuilder's own verification — rather than
    // reimplementing a subset of it — is what guarantees registration can never accept a source tree the very next
    // build is certain to reject.
    try {
      BriefBuilder.verifySettings(resolved.toAbsolutePath().normalize());
    } catch (BriefBuildException e) {
      return e.getMessage() + ".";
    }

    if (database.findSourceByPath(path).isPresent()) {
      return "The path [" + path + "] is already registered to another Organization.";
    }

    return null;
  }
}
