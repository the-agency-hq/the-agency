/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.json;

/**
 * The configuration of a Brief source: the whole of what one kind of source needs to be polled, the credential
 * included, as the single JSON document in {@code brief_sources.source_config}. One subtype per
 * {@link BriefSourceType}, discriminated by {@code type} — the same value the column beside the document carries.
 *
 * <p>A source is created the moment its kind is authorized and told what to poll afterwards, so the two are
 * separate questions: {@link #connected()} is whether the credential is there, {@link #registered()} whether the
 * thing to poll has been named. The poller has nothing to do with a source that is not both.
 *
 * <p>Every kind there is — GitHub, GitLab — is a git repository on a host the operator authorizes through OAuth,
 * so the contract is written for that shape: a {@link #connection()} the OAuth callback writes, and a repository
 * and {@link #branch()} the picker writes. Each writer replaces its own half and carries the other over
 * ({@link #withConnection}, {@link #withRepository}), so re-picking a repository never costs a re-authorization and
 * a lapsed authorization never loses the repository. The subtypes are records because the JSON codec dispatches on
 * them, which is also why this interface is flat rather than layered: a kind that is not a repository would get
 * its own subtype here and the methods it cannot answer would answer {@code null}.
 *
 * <p>A configuration also describes itself for the admin UI ({@link #details()}, {@link #url()}), so the pages that
 * show a source render what the source says about itself rather than assuming what a source consists of.
 */
@JSON
@JSONTypeInfo(property = "type")
public sealed interface BriefSourceConfig permits GitHubConfig, GitLabConfig {
  /**
   * @return The branch to build from, or {@code null} until {@link #registered()}.
   */
  String branch();

  /**
   * @return True if the source holds a usable credential — the state the admin UI reads as connected. A credential
   *     proven dead is removed from the document on the spot, so a stored one is the only connected state there is.
   */
  default boolean connected() {
    return connection() != null;
  }

  /**
   * @return The authorization the source polls with, or {@code null} while it is not connected.
   */
  OAuthConnection connection();

  /**
   * @return The rows the admin UI shows for the source once it is registered — what it builds from and where that
   *     is — in the order to show them. Empty until {@link #registered()}.
   */
  List<SourceDetail> details();

  /**
   * @return The repository as its host names it everywhere the operator has seen it — {@code owner/repository} on
   *     GitHub, the project's path with its namespace on GitLab — or {@code null} until {@link #registered()}. It
   *     is what the admin UI shows, what the picker's form posts back, and what the host's API is asked about.
   */
  String fullName();

  /**
   * @return True if the source names what it polls: a repository and a branch.
   */
  default boolean registered() {
    return fullName() != null && branch() != null;
  }

  /**
   * @return The identity the {@code brief_sources.source} column carries, which one Organization holds per kind.
   *     The repository's {@link #fullName()}, spelled as the host spells it; the {@code LOWER()} unique index makes
   *     the uniqueness case-insensitive. {@code null} until {@link #registered()}.
   */
  default String source() {
    return fullName();
  }

  BriefSourceType type();

  /**
   * @return The page on its host for what the source builds from, for the admin UI to link to, or {@code null}
   *     until {@link #registered()} — or if the kind has no such page.
   */
  String url();

  /**
   * @param connection The new authorization, or {@code null} to remove it.
   * @return This configuration with that connection and the same repository.
   */
  BriefSourceConfig withConnection(OAuthConnection connection);

  /**
   * @param fullName The repository, as {@link #fullName()} spells it.
   * @param branch   The branch to build from.
   * @return This configuration with that repository and the same connection.
   */
  BriefSourceConfig withRepository(String fullName, String branch);
}
