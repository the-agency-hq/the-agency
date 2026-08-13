/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.validation;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.error.ValidationException;

/**
 * Validates a new Organization's name. The name is display text and carries no character-set restriction: nothing
 * derives an identifier, path or URL from it, so there is nothing for a restricted alphabet to protect. It has to be
 * present, has to fit {@link #NAME_MAX_LENGTH}, and has to be unique.
 *
 * <p>The source is not validated here and cannot be: an Organization is named before it is connected to GitHub, and
 * until that connection exists the Agency has no credential with which to ask whether any particular repository is a
 * Brief source. {@link SourceValidator} does that half, at the point the operator picks a repository.
 */
public final class OrganizationValidator {
  public static final int NAME_MAX_LENGTH = 255;

  private OrganizationValidator() {
  }

  public static void validate(String name, DatabaseService database) {
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

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }
}
