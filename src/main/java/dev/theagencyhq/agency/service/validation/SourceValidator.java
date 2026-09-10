/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.validation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Validates the repository an operator picked for an Organization, before it is registered as its Brief source.
 *
 * <p>The settings marker is fetched and PARSED here, not merely looked for, and that is the whole reason this class
 * runs at registration time at all. Without it, a repository the Agency cannot build registers cleanly and then
 * fails on every poll cycle from then on, with the only evidence a {@code BUILD_FAILED} status on a detail page
 * nobody has a reason to open yet. Calling {@code BriefBuilder}'s own verification — rather than reimplementing a
 * subset of it — is what guarantees registration can never accept a repository the very next build is certain to
 * reject.
 *
 * <p>It costs two requests to the host: one to resolve the branch, one to read a file that is a few dozen bytes.
 * That is the cheapest possible way to answer the question, and answering it late is what makes it expensive.
 */
public final class SourceValidator {
  private SourceValidator() {
  }

  /**
   * @param type           The kind of source the repository is being registered as.
   * @param organizationId The Organization the repository is being registered to. Its own current source does not
   *                       count against the one-repository-per-Organization rule, so picking the repository it
   *                       already holds — to change the branch, or to change nothing — is not a collision.
   * @param fullName       The repository as the host names it, as the form supplied it.
   * @param branch         The branch to build from, as the form supplied it.
   * @param accessToken    The Organization's token for the host.
   * @param sources        The sources, for the uniqueness check.
   * @param client         The host's client.
   * @throws RepositoryUnauthorizedException If the host rejected the token — the connection's problem rather than
   *     the repository's, so it is the caller's to handle, not a validation error.
   * @throws ValidationException with every reason this repository cannot be registered.
   */
  public static void validate(BriefSourceType type, UUID organizationId, String fullName, String branch,
                              String accessToken, BriefSourceRepository sources, RepositoryClient client) {
    var errors = new ArrayList<String>();
    var trimmedName = fullName == null ? "" : fullName.trim();
    var trimmedBranch = branch == null ? "" : branch.trim();

    // The form carries the repository as the host names it, which on both hosts is a path with at least one
    // slash and something on either side of it: `owner/repository`, `group/project`.
    var slash = trimmedName.indexOf('/');
    if (slash <= 0 || slash == trimmedName.length() - 1) {
      errors.add("A " + type.label() + " repository is required.");
    }
    if (trimmedBranch.isEmpty()) {
      errors.add("A branch is required.");
    }

    // Only when the fields are present at all: everything below asks the host about them, and asking about an
    // empty string produces a second, less useful error for a mistake already reported.
    if (errors.isEmpty()) {
      var error = repositoryError(type, organizationId, trimmedName, trimmedBranch, accessToken, sources, client);
      if (error != null) {
        errors.add(error);
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  /**
   * @return The first reason this repository cannot be registered, or {@code null} if it can. Only the first,
   *     because the later checks presuppose the earlier ones — asking for a file on a branch that does not exist, or
   *     parsing a settings file the repository does not have, produces noise rather than a second useful error.
   */
  private static String repositoryError(BriefSourceType type, UUID organizationId, String fullName, String branch,
                                        String accessToken, BriefSourceRepository sources, RepositoryClient client) {
    var registered = sources.findBySource(type, fullName).orElse(null);
    if (registered != null && !registered.organizationId().equals(organizationId)) {
      return "The repository [" + fullName + "] is already registered to another Organization.";
    }

    var label = type.label();
    byte[] settings;
    try {
      if (client.head(accessToken, fullName, branch) == null) {
        return label + " has no branch [" + branch + "] in [" + fullName + "], or the repository is not one this "
            + label + " account has given The Agency access to.";
      }

      settings = client.readFile(accessToken, fullName, branch, BriefBuilder.SETTINGS_FILE);
    } catch (RepositoryUnauthorizedException e) {
      // Deliberately not turned into a validation error: "retry" is the wrong instruction for a dead credential,
      // and the caller owns the connection and what happens to a credential the host has refused.
      throw e;
    } catch (RepositoryException e) {
      // A transport failure or an unexpected status. Reported as a validation error rather than a 500 because the
      // operator can act on it -- retrying is the whole of the fix -- and because a stack trace on a form is not an
      // improvement over a sentence.
      return label + " could not be reached to check [" + fullName + "]: " + e.getMessage();
    }

    try {
      BriefBuilder.verifySettings(settings, fullName + "@" + branch);
    } catch (BriefBuildException e) {
      return e.getMessage() + ".";
    }

    return null;
  }
}
