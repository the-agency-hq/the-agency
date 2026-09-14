/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.bitbucket;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * A Bitbucket Cloud Brief source: the authorization the operator granted, and the repository its Briefs are built
 * from.
 *
 * <p>The repository is one member, its full name as Bitbucket spells it ({@code workspace/repository}), the same
 * string Bitbucket uses in its own URLs and the one its API returns as {@code full_name}. A workspace slug cannot
 * contain a slash, so the first one separates the two halves when the API needs them apart. It is stored as
 * Bitbucket spells it; uniqueness across Organizations is case-insensitive through the {@code LOWER()} unique index
 * on the {@code source} column.
 *
 * <p>No instance origin, unlike {@link dev.theagencyhq.agency.model.gitlab.GitLabConfig}: this is Bitbucket Cloud
 * only. Bitbucket Data Center is a different product with a different API, and is not a kind of source the Agency
 * offers.
 *
 * @param connection The Bitbucket authorization, or {@code null} while the source is not connected.
 * @param repository The repository's full name, or {@code null} until a repository is picked.
 * @param branch     The branch to build from, or {@code null} until a repository is picked.
 */
@JSON
@JSONSubtype("BITBUCKET")
public record BitbucketConfig(OAuthConnection connection, String repository, String branch)
    implements BriefSourceConfig {
  public static final String WEB_URL = "https://bitbucket.org";

  public BitbucketConfig {
    repository = repository == null ? null : repository.trim();
    branch = branch == null ? null : branch.trim();
  }

  @Override
  public List<SourceDetail> details() {
    return registered()
        ? List.of(new SourceDetail("Repository", repository, url()), new SourceDetail("Branch", branch, null))
        : List.of();
  }

  @Override
  public String fullName() {
    return repository;
  }

  @Override
  public BriefSourceType type() {
    return BriefSourceType.BITBUCKET;
  }

  /**
   * @return The repository's page on bitbucket.org, or {@code null} until a repository is picked.
   */
  @Override
  public String url() {
    return registered() ? WEB_URL + "/" + repository : null;
  }

  @Override
  public BitbucketConfig withConnection(OAuthConnection connection) {
    return new BitbucketConfig(connection, repository, branch);
  }

  @Override
  public BitbucketConfig withRepository(String fullName, String branch) {
    return new BitbucketConfig(connection, fullName, branch);
  }
}
