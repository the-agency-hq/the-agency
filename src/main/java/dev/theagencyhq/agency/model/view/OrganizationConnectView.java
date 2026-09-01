/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * The repository picker: pointing a GitHub-connected Organization at the repository its Briefs are built from.
 * Only ever rendered for a connected Organization — an unconnected one is redirected to the Organization's own
 * page, which is where the connection is offered — so the page has two states rather than three: nothing to offer
 * ({@code repositories} is empty because the App is not installed on any account the credential can see, and the
 * page sends the operator to install it), and the picker. Both send the operator to GitHub through
 * {@code GitHubController.install}, which brings them back here once GitHub is done with them.
 *
 * @param organization      The Organization being pointed at a repository, carrying the GitHub connection whose
 *                          repositories the picker lists.
 * @param source            Its current source, or {@code null} if it has never had one.
 * @param repositories      Every repository the credential can offer, as {@code owner/name}, sorted.
 * @param defaultBranches   The default branch of each repository in {@code repositories}, keyed by the same
 *                          {@code owner/name}. The form pre-fills from this so the common case needs no typing.
 * @param status            How the trip to GitHub that just returned here ended, lowercased, or {@code null} if
 *                          the page was reached any other way: {@code installed} when an installation was created
 *                          or changed, {@code install_requested} when the operator could only ask that account's
 *                          admins to install it. Rendered from that fixed set, never echoed.
 * @param errors            Why the last repository submission was rejected, or empty.
 * @param selectedFullName  The {@code owner/name} the last submission carried, so a rejected form comes back filled
 *                          in.
 * @param branch            The branch the last submission carried.
 */
public record OrganizationConnectView(Organization organization, BriefSource source, List<String> repositories,
                                      Map<String, String> defaultBranches, String status, List<String> errors,
                                      String selectedFullName, String branch) {
  /**
   * @return The GitHub account the Organization is connected as. Never {@code null}: this page is only rendered
   *     connected — an unconnected Organization is redirected to its own page before the view is built.
   */
  public String githubLogin() {
    return organization.gitHubConnection().login();
  }
}
