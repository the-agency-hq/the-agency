/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The repository picker: pointing a connected source at the repository its Briefs are built from. Only ever
 * rendered for a connected source — an unconnected Organization is redirected to its Sources page, which is where
 * the connection is offered — so the page has two states rather than three: nothing to offer ({@code repositories}
 * is empty), and the picker. What "nothing to offer" means and what fixes it is the one thing that differs by
 * kind: on GitHub the App is installed on no account the credential can see, and the page sends the operator to
 * install it through {@code RepositorySourceController.install}, which brings them back here; on GitLab the
 * account is a member of no project, and the fix happens on GitLab.
 *
 * @param type             The kind of source the picker is for.
 * @param organization     The Organization whose source is being pointed at a repository.
 * @param config           Its source's configuration, carrying the connection whose repositories the picker lists
 *                         and the repository it currently builds from, if any.
 * @param repositories     Every repository the credential can offer, as the host names them, sorted.
 * @param defaultBranches  The default branch of each repository in {@code repositories}, keyed by the same name.
 *                         The form pre-fills from this so the common case needs no typing.
 * @param errors           Why the last repository submission was rejected, or empty.
 * @param selectedFullName The repository the last submission carried, so a rejected form comes back filled in.
 * @param branch           The branch the last submission carried.
 */
public record RepositorySourceView(BriefSourceType type, Organization organization, BriefSourceConfig config,
                                   List<String> repositories, Map<String, String> defaultBranches,
                                   List<String> errors, String selectedFullName, String branch) {
  /**
   * @return The account the source is connected as. Never {@code null}: this page is only rendered connected — an
   *     unconnected Organization is redirected to its Sources page before the view is built.
   */
  public String login() {
    return config.connection().login();
  }
}
