/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.validation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Validates the GitHub repository an operator picked for an Organization, before it is registered as its Brief
 * source.
 *
 * <p>The settings marker is fetched and PARSED here, not merely looked for, and that is the whole reason this class
 * runs at registration time at all. Without it, a repository the Agency cannot build registers cleanly and then
 * fails on every poll cycle from then on, with the only evidence a {@code BUILD_FAILED} status on a detail page
 * nobody has a reason to open yet. Calling {@code BriefBuilder}'s own verification — rather than reimplementing a
 * subset of it — is what guarantees registration can never accept a repository the very next build is certain to
 * reject.
 *
 * <p>It costs two GitHub requests: one to resolve the branch, one to read a file that is a few dozen bytes. That is
 * the cheapest possible way to answer the question, and answering it late is what makes it expensive.
 */
public final class SourceValidator {
  private SourceValidator() {
  }

  /**
   * @param owner       The repository owner, as the form supplied it.
   * @param repository  The repository name, as the form supplied it.
   * @param branch      The branch to build from, as the form supplied it.
   * @param accessToken The connecting user's GitHub token.
   * @param database    The database, for the uniqueness check.
   * @param github      The GitHub client.
   * @throws GitHubUnauthorizedException If GitHub rejected the token — the connection's problem rather than the
   *     repository's, so it is the caller's to handle, not a validation error.
   * @throws ValidationException with every reason this repository cannot be registered.
   */
  public static void validate(String owner, String repository, String branch, String accessToken,
                              DatabaseService database, GitHubClient github) {
    var errors = new ArrayList<String>();
    var trimmedOwner = owner == null ? "" : owner.trim();
    var trimmedRepository = repository == null ? "" : repository.trim();
    var trimmedBranch = branch == null ? "" : branch.trim();

    if (trimmedOwner.isEmpty() || trimmedRepository.isEmpty()) {
      errors.add("A GitHub repository is required.");
    }
    if (trimmedBranch.isEmpty()) {
      errors.add("A branch is required.");
    }

    // Only when the fields are present at all: everything below asks GitHub about them, and asking about an empty
    // string produces a second, less useful error for a mistake already reported.
    if (errors.isEmpty()) {
      var error = repositoryError(trimmedOwner, trimmedRepository, trimmedBranch, accessToken, database, github);
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
  private static String repositoryError(String owner, String repository, String branch, String accessToken,
                                        DatabaseService database, GitHubClient github) {
    var fullName = owner + "/" + repository;
    if (database.findSourceByRepository(owner, repository).isPresent()) {
      return "The repository [" + fullName + "] is already registered to another Organization.";
    }

    byte[] settings;
    try {
      if (github.head(accessToken, owner, repository, branch) == null) {
        return "GitHub has no branch [" + branch + "] in [" + fullName + "], or the repository is not one this "
            + "GitHub account has given The Agency access to.";
      }

      settings = github.readFile(accessToken, owner, repository, branch, BriefBuilder.SETTINGS_FILE);
    } catch (GitHubUnauthorizedException e) {
      // Deliberately not turned into a validation error: "retry" is the wrong instruction for a dead credential,
      // and the caller owns the connection and what happens to a credential GitHub has refused.
      throw e;
    } catch (GitHubException e) {
      // A transport failure or an unexpected status. Reported as a validation error rather than a 500 because the
      // operator can act on it -- retrying is the whole of the fix -- and because a stack trace on a form is not an
      // improvement over a sentence.
      return "GitHub could not be reached to check [" + fullName + "]: " + e.getMessage();
    }

    try {
      BriefBuilder.verifySettings(settings, fullName + "@" + branch);
    } catch (BriefBuildException e) {
      return e.getMessage() + ".";
    }

    return null;
  }
}
