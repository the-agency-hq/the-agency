/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.github;

import module java.base;

import dev.theagencyhq.agency.model.github.*;

/**
 * Everything the Agency asks GitHub for, as one interface so the whole of it can be replaced in tests without a
 * network. Two OAuth calls against {@code github.com}, and five read calls against {@code api.github.com} made with
 * a user-to-server token.
 *
 * <p>Implementations never throw a checked exception, and report three different kinds of outcome three different
 * ways. A call that could not be completed at all raises {@link GitHubException}, which is transient and clears
 * itself. A call GitHub refused the credential for raises {@link GitHubUnauthorizedException}, which never clears
 * itself and needs a human to authorize again. A call whose answer is simply "there is nothing there" — a branch
 * that is gone, a repository this installation does not cover, a file that does not exist — returns {@code null}.
 * Three outcomes, three responses; collapsing any two of them would leave a caller retrying something that will
 * never work, or asking for help with something that would have fixed itself.
 */
public interface GitHubClient {
  /**
   * Turns an authorization code into a user-to-server credential via
   * {@code POST https://github.com/login/oauth/access_token}.
   *
   * @param code        The code GitHub sent to the callback.
   * @param redirectURI The redirect URI the authorize request used. GitHub compares it exactly.
   * @return The credential, or {@code null} if GitHub rejected the exchange.
   */
  GitHubTokens exchangeCode(String code, String redirectURI);

  /**
   * @param accessToken   The user's token.
   * @param owner         The repository owner.
   * @param repository    The repository name.
   * @param ref           A branch, tag, or commit SHA.
   * @return The commit SHA {@code ref} currently points at, or {@code null} if the ref or the repository is not
   *     visible to this token. This is the poll: it is one request, and it is the only one a cycle makes when
   *     nothing has changed.
   * @throws GitHubUnauthorizedException If GitHub rejected the token.
   */
  String head(String accessToken, String owner, String repository, String ref);

  /**
   * @param accessToken The user's token.
   * @return Every installation of the Agency's GitHub App this user can reach. Empty when they have installed it
   *     nowhere, which is an ordinary state for someone who has just authorized it.
   * @throws GitHubUnauthorizedException If GitHub rejected the token.
   */
  List<GitHubInstallation> installations(String accessToken);

  /**
   * Reads one file at one ref via {@code GET /repos/{owner}/{repo}/contents/{path}}. Used to check a repository is
   * a Brief source before registering it, which is a question about a single small file and must not pay for a
   * whole-repository download to answer.
   *
   * @param accessToken The user's token.
   * @param owner       The repository owner.
   * @param repository  The repository name.
   * @param ref         A branch, tag, or commit SHA.
   * @param path        The repository-relative path.
   * @return The file's bytes, or {@code null} if the file, the ref, or the repository is not there.
   * @throws GitHubUnauthorizedException If GitHub rejected the token.
   */
  byte[] readFile(String accessToken, String owner, String repository, String ref, String path);

  /**
   * @param accessToken    The user's token.
   * @param installationId The installation to list.
   * @return The repositories that installation covers and this user can see.
   * @throws GitHubUnauthorizedException If GitHub rejected the token.
   */
  List<GitHubRepository> repositories(String accessToken, long installationId);

  /**
   * Exchanges a refresh token for a fresh credential. The same endpoint as
   * {@link #exchangeCode(String, String)}, with {@code grant_type=refresh_token}.
   *
   * @param refreshToken The refresh token from the stored credential.
   * @return The new credential, or {@code null} if GitHub rejected the refresh — which is what a revoked
   *     installation or an expired refresh token looks like, and is permanent until the operator reconnects.
   */
  GitHubTokens refresh(String refreshToken);

  /**
   * Downloads a whole repository at one commit: the zipball for the content, and the recursive tree for the file
   * modes the zipball cannot carry.
   *
   * @param accessToken The user's token.
   * @param owner       The repository owner.
   * @param repository  The repository name.
   * @param commit      The commit SHA to fetch. A SHA rather than a branch on purpose — the poller resolves the ref
   *                    to a SHA first, and passing the branch here would let the two halves of the download come
   *                    from two different commits if somebody pushed in between.
   * @return The repository's blobs and their modes.
   * @throws GitHubException             If the download fails, is too large, or the tree comes back truncated.
   * @throws GitHubUnauthorizedException If GitHub rejected the token.
   */
  RepositoryContents contents(String accessToken, String owner, String repository, String commit);

  /**
   * @param accessToken The user's token.
   * @return The account that token belongs to, or {@code null} if the token is not valid.
   */
  GitHubUser user(String accessToken);
}
