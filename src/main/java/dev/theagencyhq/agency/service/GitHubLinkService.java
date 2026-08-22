/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import java.lang.System.Logger.*;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.github.*;
import dev.theagencyhq.agency.model.github.GitHubConnection;
import dev.theagencyhq.agency.model.Organization;
import org.lattejava.web.Configuration;

/**
 * Owns the Agency's half of an Organization's GitHub authorization: completing the OAuth exchange, storing the
 * resulting credential, and handing out a working access token to whatever needs one.
 *
 * <p>The credential lives in columns on the Organization's own {@code organizations} row. That gives it exactly
 * the lifetime it should have: deleting the Organization takes its GitHub credential with it, with nothing to
 * remember to clean up. It also makes the authorization the Organization's rather than any operator's — the
 * operator who happens to complete the handshake grants a credential to the Organization, and their own account's
 * later fate does not take the Organization down with it.
 *
 * <p>Nothing here caches. A token is read from the database on each use and refreshed in place when it is close to
 * expiring, so an authorization revoked on GitHub's side stops working on the next cycle rather than whenever a
 * cache happened to expire.
 *
 * <p>A credential proven dead — a refresh GitHub rejects, or one with no usable refresh token left — is removed
 * from the row on the spot. The admin UI reads a stored credential as a working connection, so a dead one left in
 * place would keep every page offering actions that can never work instead of the reconnect that fixes them.
 */
public class GitHubLinkService {
  /**
   * How long before its expiry an access token is treated as already expired. A token that has four minutes left
   * is refreshed rather than handed to a build that can take longer than that to finish.
   */
  public static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
  private static final System.Logger logger = System.getLogger(GitHubLinkService.class.getName());
  private final String clientId;
  private final DatabaseService database;
  private final GitHubClient github;

  public GitHubLinkService(Configuration config, DatabaseService database, GitHubClient github) {
    this.clientId = config.get("github.clientId");
    this.database = database;
    this.github = github;
  }

  /**
   * Resolves a working GitHub access token for an Organization, refreshing the stored credential if it is expired
   * or close to it.
   *
   * <p>This is the method the poller calls once per source per cycle, and everything it can go wrong with is
   * reported the same way: {@code null}. The distinctions between "never connected", "the refresh token expired"
   * and "the installation was removed on GitHub" are all the same instruction to the operator — reconnect this
   * Organization — so the caller does not need to tell them apart, and the log line here carries the detail for
   * whoever is actually diagnosing it.
   *
   * @param organizationId The Organization.
   * @return A usable access token, or {@code null} if this Organization has no working GitHub authorization.
   */
  public String accessToken(UUID organizationId) {
    return accessToken(organizationId, connection(organizationId));
  }

  /**
   * The same resolution as {@link #accessToken(UUID)}, for a caller that has already read the Organization's row
   * and so already holds its connection — resolving a token from it must not fetch the same row a second time.
   *
   * @param organizationId The Organization, which a refreshed credential is written back against.
   * @param connection     The stored connection as the caller read it, or {@code null} if the Organization holds
   *                       none.
   * @return A usable access token, or {@code null} if this Organization has no working GitHub authorization.
   */
  public String accessToken(UUID organizationId, GitHubConnection connection) {
    if (connection == null) {
      return null;
    }

    var tokens = connection.tokens();
    var now = Instant.now();
    if (tokens.accessTokenValid(now, REFRESH_SKEW)) {
      return tokens.accessToken();
    }

    if (!tokens.refreshable(now)) {
      logger.log(Level.WARNING, "The GitHub authorization for Organization [{0}] has expired and carries no usable refresh token, so it must be granted again", organizationId);
      database.clearGitHubConnection(organizationId, now);
      return null;
    }

    var refreshed = github.refresh(tokens.refreshToken());
    if (refreshed == null) {
      logger.log(Level.WARNING, "GitHub rejected the refresh token for Organization [{0}], so the authorization must be granted again", organizationId);
      database.clearGitHubConnection(organizationId, now);
      return null;
    }

    database.updateGitHubConnection(organizationId, new GitHubConnection(connection.login(), refreshed), now);
    return refreshed.accessToken();
  }

  /**
   * Builds the URL that starts the GitHub authorization.
   *
   * @param redirectURI Where GitHub returns the browser.
   * @param state       The opaque value the callback checks against its cookie.
   * @return The absolute URL to redirect the browser to.
   */
  public String authorizeURL(String redirectURI, String state) {
    return GitHubHTTPClient.authorizeURL(clientId, redirectURI, state);
  }

  /**
   * Completes the OAuth exchange and stores the credential against the Organization, replacing any credential
   * already there.
   *
   * @param organizationId The Organization the connection was started for.
   * @param code           The authorization code GitHub sent to the callback.
   * @param redirectURI    The redirect URI the authorize request used.
   * @return The result, which the callback turns into a message on the page it returns to.
   */
  public LinkResult link(UUID organizationId, String code, String redirectURI) {
    var tokens = github.exchangeCode(code, redirectURI);
    if (tokens == null) {
      return LinkResult.EXCHANGE_FAILED;
    }

    var githubUser = github.user(tokens.accessToken());
    if (githubUser == null) {
      return LinkResult.EXCHANGE_FAILED;
    }

    if (!database.updateGitHubConnection(organizationId, new GitHubConnection(githubUser.login(), tokens), Instant.now())) {
      logger.log(Level.ERROR, "Unable to store the GitHub credential for Organization [{0}], which no longer exists", organizationId);
      return LinkResult.LINK_FAILED;
    }

    return LinkResult.LINKED;
  }

  /**
   * Removes this Organization's GitHub credential, if there is one. Idempotent.
   *
   * @param organizationId The Organization.
   */
  public void unlink(UUID organizationId) {
    database.clearGitHubConnection(organizationId, Instant.now());
  }

  private GitHubConnection connection(UUID organizationId) {
    return database.findOrganization(organizationId).map(Organization::gitHubConnection).orElse(null);
  }

  /**
   * What happened when an operator tried to connect an Organization to GitHub.
   */
  public enum LinkResult {
    /**
     * GitHub would not turn the callback's code into a token. Almost always a code that was already used or has
     * expired, which a reload of the callback URL produces on its own.
     */
    EXCHANGE_FAILED,
    /**
     * GitHub authorized the operator, but there was no Organization left to store the credential against — it was
     * deleted while the operator was away authorizing.
     */
    LINK_FAILED,
    LINKED,
    /**
     * The callback did not come from a connection this browser started. A missing, unreadable, or mismatched state
     * cookie — which is what a cross-site forgery of this endpoint looks like.
     */
    STATE_MISMATCH
  }
}
