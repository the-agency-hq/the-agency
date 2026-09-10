/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.github;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * A GitHub Brief source: the authorization the operator granted, and the repository its Briefs are built from.
 *
 * <p>{@code owner} and {@code repository} are stored as GitHub spells them, because that is what the admin UI shows
 * and what a URL is built from; uniqueness across Organizations is case-insensitive, enforced by the {@code LOWER()}
 * unique index on the {@code source} column rather than by flattening the stored value. The two are separate
 * members rather than one {@code owner/repository} because that is the document every connected GitHub source
 * already carries; {@link #fullName()} joins them for everything above the row.
 *
 * @param connection The GitHub authorization, or {@code null} while the source is not connected.
 * @param owner      The repository owner, or {@code null} until a repository is picked.
 * @param repository The repository name, or {@code null} until a repository is picked.
 * @param branch     The branch to build from, or {@code null} until a repository is picked.
 */
@JSON
@JSONSubtype("GITHUB")
public record GitHubConfig(OAuthConnection connection, String owner, String repository, String branch)
    implements BriefSourceConfig {
  public static final String WEB_URL = "https://github.com";

  public GitHubConfig {
    owner = owner == null ? null : owner.trim();
    repository = repository == null ? null : repository.trim();
    branch = branch == null ? null : branch.trim();
  }

  /**
   * @param owner      The repository owner.
   * @param repository The repository name.
   * @return {@code owner/repository}, the form GitHub itself uses everywhere and the one the admin UI renders — and
   *     the {@link #source()} identity a registered GitHub source carries.
   */
  public static String fullName(String owner, String repository) {
    return owner + "/" + repository;
  }

  @Override
  public List<SourceDetail> details() {
    return registered()
        ? List.of(new SourceDetail("Repository", fullName(), url()), new SourceDetail("Branch", branch, null))
        : List.of();
  }

  /**
   * @return {@code owner/repository}, or {@code null} until a repository is picked.
   */
  @Override
  public String fullName() {
    return owner != null && repository != null ? fullName(owner, repository) : null;
  }

  @Override
  public BriefSourceType type() {
    return BriefSourceType.GITHUB;
  }

  /**
   * @return The repository's page on github.com, or {@code null} until a repository is picked.
   */
  @Override
  public String url() {
    return registered() ? WEB_URL + "/" + fullName() : null;
  }

  @Override
  public GitHubConfig withConnection(OAuthConnection connection) {
    return new GitHubConfig(connection, owner, repository, branch);
  }

  /**
   * @param fullName The repository as {@code owner/repository}. A GitHub owner cannot contain a slash, so the first
   *                 one is the separator.
   * @param branch   The branch to build from.
   * @return This configuration with that repository and the same connection.
   */
  @Override
  public GitHubConfig withRepository(String fullName, String branch) {
    var slash = fullName.indexOf('/');
    return new GitHubConfig(connection, fullName.substring(0, slash), fullName.substring(slash + 1), branch);
  }
}
