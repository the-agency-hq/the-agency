/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.source;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Everything the Agency asks a repository host for, as one interface per host so the whole of it can be replaced in
 * tests without a network: the two OAuth grants, and the reads made with the token they produce. GitHub, GitLab and
 * Bitbucket each implement it once; everything above — the OAuth handshake, the picker, the validator, the poller —
 * is written against this and told apart nothing but which host it is talking to.
 *
 * <p>A repository is named by its {@code fullName} throughout: {@code owner/repository} on GitHub, the project's
 * path with its namespace on GitLab, {@code workspace/repository} on Bitbucket. It is the same string the host shows
 * the operator, the one the picker's form posts back, and the one {@code BriefSourceConfig#fullName()} stores, so
 * nothing translates it on the way through.
 *
 * <p>Implementations never throw a checked exception, and report three different kinds of outcome three different
 * ways. A call that could not be completed at all raises {@link RepositoryException}, which is transient and clears
 * itself. A call the host refused the credential for raises {@link RepositoryUnauthorizedException}, which never
 * clears itself and needs a human to authorize again. A call whose answer is simply "there is nothing there" — a
 * branch that is gone, a repository this token cannot see, a file that does not exist — returns {@code null}.
 * Three outcomes, three responses; collapsing any two of them would leave a caller retrying something that will
 * never work, or asking for help with something that would have fixed itself.
 */
public interface RepositoryClient {
  /**
   * Downloads a whole repository at one commit: the archive for the content, and the recursive tree for the file
   * modes the archive cannot carry.
   *
   * @param accessToken The token.
   * @param fullName    The repository.
   * @param commit      The commit SHA to fetch. A SHA rather than a branch on purpose — the poller resolves the ref
   *                    to a SHA first, and passing the branch here would let the two halves of the download come
   *                    from two different commits if somebody pushed in between.
   * @return The repository's blobs and their modes.
   * @throws RepositoryException             If the download fails, is too large, or the tree cannot be read in full.
   * @throws RepositoryUnauthorizedException If the host rejected the token.
   */
  RepositoryContents contents(String accessToken, String fullName, String commit);

  /**
   * Turns an authorization code into a credential.
   *
   * @param code        The code the host sent to the callback.
   * @param redirectURI The redirect URI the authorize request used. The hosts compare it exactly.
   * @return The credential, or {@code null} if the host rejected the exchange.
   */
  OAuthTokens exchangeCode(String code, String redirectURI);

  /**
   * @param accessToken The token.
   * @param fullName    The repository.
   * @param ref         A branch, tag, or commit SHA.
   * @return The commit SHA {@code ref} currently points at, or {@code null} if the ref or the repository is not
   *     visible to this token. This is the poll: it is one request, and it is the only one a cycle makes when
   *     nothing has changed.
   * @throws RepositoryUnauthorizedException If the host rejected the token.
   */
  String head(String accessToken, String fullName, String ref);

  /**
   * @param accessToken The token.
   * @return The account that token belongs to, as the host names it, or {@code null} if the token is not valid.
   */
  String login(String accessToken);

  /**
   * Reads one file at one ref. Used to check a repository is a Brief source before registering it, which is a
   * question about a single small file and must not pay for a whole-repository download to answer.
   *
   * @param accessToken The token.
   * @param fullName    The repository.
   * @param ref         A branch, tag, or commit SHA.
   * @param path        The repository-relative path.
   * @return The file's bytes, or {@code null} if the file, the ref, or the repository is not there.
   * @throws RepositoryUnauthorizedException If the host rejected the token.
   */
  byte[] readFile(String accessToken, String fullName, String ref, String path);

  /**
   * Exchanges a refresh token for a fresh credential.
   *
   * @param refreshToken The refresh token from the stored credential.
   * @return The new credential, or {@code null} if the host rejected the refresh — which is what a revoked
   *     authorization or an expired refresh token looks like, and is permanent until the operator reconnects.
   */
  OAuthTokens refresh(String refreshToken);

  /**
   * @param accessToken The token.
   * @return Every repository the token can offer as a Brief source, in no particular order. Empty is an ordinary
   *     state — on GitHub, an operator who has authorized the App but installed it nowhere; on GitLab, an account
   *     that is a member of no project; on Bitbucket, an account that can read no repository.
   * @throws RepositoryUnauthorizedException If the host rejected the token.
   */
  List<RepositorySummary> repositories(String accessToken);
}
