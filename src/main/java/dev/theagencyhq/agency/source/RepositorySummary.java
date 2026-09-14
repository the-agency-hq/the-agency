/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.source;

/**
 * One repository the picker can offer, as {@link RepositoryClient#repositories} lists them: the host's own wire
 * shapes ({@code GitHubRepository}, {@code GitLabProject}, {@code BitbucketRepository}) reduced to the two members
 * the picker renders.
 *
 * @param fullName      The repository as the host names it — what the picker shows and what the form posts back.
 * @param defaultBranch The branch a source registers against unless the operator names another, or {@code null}
 *                      if the host reports none, as GitLab does for an empty project.
 */
public record RepositorySummary(String fullName, String defaultBranch) {
}
