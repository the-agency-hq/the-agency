/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.gitlab;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * A GitLab Brief source: the authorization the operator granted, the instance it was granted on, and the project
 * its Briefs are built from.
 *
 * <p>The project is one member, its path with its namespace ({@code group/subgroup/project}), rather than an
 * owner and a name as {@link GitHubConfig} holds them: GitLab groups nest, so the namespace half can itself contain
 * slashes, and GitLab's API takes the whole path as one URL-encoded value anyway. It is stored as GitLab spells it;
 * uniqueness across Organizations is case-insensitive through the {@code LOWER()} unique index on the
 * {@code source} column.
 *
 * <p>The instance's origin is stored with the source, because GitLab is routinely self-managed and the source
 * belongs to the instance it was authorized on: its page is a URL on that instance, and a later change to the
 * server's {@code gitlab.baseURL} does not move where an existing source lives.
 *
 * @param connection The GitLab authorization, or {@code null} while the source is not connected.
 * @param baseURL    The GitLab instance's origin, with no trailing slash — {@code https://gitlab.com} or a
 *                   self-managed instance.
 * @param project    The project's path with its namespace, or {@code null} until a project is picked.
 * @param branch     The branch to build from, or {@code null} until a project is picked.
 */
@JSON
@JSONSubtype("GITLAB")
public record GitLabConfig(OAuthConnection connection, String baseURL, String project, String branch)
    implements BriefSourceConfig {
  public GitLabConfig {
    baseURL = baseURL == null ? null : baseURL.trim().endsWith("/")
        ? baseURL.trim().substring(0, baseURL.trim().length() - 1) : baseURL.trim();
    project = project == null ? null : project.trim();
    branch = branch == null ? null : branch.trim();
  }

  @Override
  public List<SourceDetail> details() {
    return registered()
        ? List.of(new SourceDetail("Project", project, url()), new SourceDetail("Branch", branch, null))
        : List.of();
  }

  @Override
  public String fullName() {
    return project;
  }

  @Override
  public BriefSourceType type() {
    return BriefSourceType.GITLAB;
  }

  /**
   * @return The project's page on its instance, or {@code null} until a project is picked.
   */
  @Override
  public String url() {
    return registered() && baseURL != null ? baseURL + "/" + project : null;
  }

  @Override
  public GitLabConfig withConnection(OAuthConnection connection) {
    return new GitLabConfig(connection, baseURL, project, branch);
  }

  @Override
  public GitLabConfig withRepository(String fullName, String branch) {
    return new GitLabConfig(connection, baseURL, fullName, branch);
  }
}
