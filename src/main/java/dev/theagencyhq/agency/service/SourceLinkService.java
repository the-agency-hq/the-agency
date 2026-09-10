/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;

import java.lang.System.Logger.*;

/**
 * Owns the Agency's half of an Organization's authorization with a repository host: completing the OAuth exchange,
 * storing the resulting credential, and handing out a working access token to whatever needs one. The same code
 * for every kind of source; only the client it talks to differs, resolved from the {@link SourceCatalog} by the
 * source's type.
 *
 * <p>The credential lives inside the Organization's own {@code brief_sources} row, as the {@code connection}
 * member of its {@link BriefSourceConfig}. That gives it exactly the lifetime it should have: the credential and
 * the repository it reads are one source, deleting the Organization takes both with it, and nothing has to be
 * remembered to clean up. It also makes the authorization the Organization's rather than any operator's — the
 * operator who happens to complete the handshake grants a credential to the Organization, and their own account's
 * later fate does not take the Organization down with it.
 *
 * <p>Every write here is a whole source row: the row is read, the connection member is replaced, and the row is
 * written back with its repository and poll history exactly as they were. Nothing here caches. A token is read from
 * the database on each use and refreshed in place when it is close to expiring, so an authorization revoked on the
 * host's side stops working on the next cycle rather than whenever a cache happened to expire.
 *
 * <p>A credential proven dead — a refresh the host rejects, or one with no usable refresh token left — is removed
 * from the source on the spot. The admin UI reads a stored credential as a working connection, so a dead one left
 * in place would keep every page offering actions that can never work instead of the reconnect that fixes them.
 */
@Prototype
public class SourceLinkService {
  /**
   * How long before its expiry an access token is treated as already expired. A token that has four minutes left
   * is refreshed rather than handed to a build that can take longer than that to finish.
   */
  public static final Duration REFRESH_SKEW = Duration.ofMinutes(5);
  private static final System.Logger logger = System.getLogger(SourceLinkService.class.getName());
  private final SourceCatalog catalog;
  private final OrganizationRepository organizations;
  private final BriefSourceRepository sources;

  public SourceLinkService(SourceCatalog catalog, BriefSourceRepository sources, OrganizationRepository organizations) {
    this.catalog = catalog;
    this.organizations = organizations;
    this.sources = sources;
  }

  /**
   * @param source An Organization's source, or {@code null} if it has none.
   * @return The authorization the source holds, or {@code null} if it is not connected — or there is no source.
   */
  public static OAuthConnection connection(BriefSource source) {
    return source == null ? null : source.config().connection();
  }

  /**
   * Resolves a working access token for an Organization's source, refreshing the stored credential if it is
   * expired or close to it.
   *
   * <p>Everything it can go wrong with is reported the same way: {@code null}. The distinctions between "never
   * connected", "the refresh token expired" and "the authorization was revoked on the host" are all the same
   * instruction to the operator — reconnect this Organization — so the caller does not need to tell them apart,
   * and the log line here carries the detail for whoever is actually diagnosing it.
   *
   * @param organizationId The Organization.
   * @return A usable access token, or {@code null} if this Organization has no working authorization.
   */
  public String accessToken(UUID organizationId) {
    var source = sources.findByOrganizationId(organizationId).orElse(null);
    return source == null ? null : accessToken(organizationId, source.config());
  }

  /**
   * The same resolution as {@link #accessToken(UUID)}, for a caller that has already read the Organization's source
   * and so already holds its configuration — resolving a token from it must not fetch the same row a second time.
   *
   * @param organizationId The Organization, which a refreshed credential is written back against.
   * @param config         The source's configuration as the caller read it, or {@code null} if the Organization
   *                       has no source.
   * @return A usable access token, or {@code null} if this Organization has no working authorization.
   */
  public String accessToken(UUID organizationId, BriefSourceConfig config) {
    if (config == null || config.connection() == null) {
      return null;
    }

    var tokens = config.connection().tokens();
    var now = Instant.now();
    if (tokens.accessTokenValid(now, REFRESH_SKEW)) {
      return tokens.accessToken();
    }

    var label = config.type().label();
    if (!tokens.refreshable(now)) {
      logger.log(Level.WARNING, "The {0} authorization for Organization [{1}] has expired and carries no usable refresh token, so it must be granted again", label, organizationId);
      unlink(organizationId);
      return null;
    }

    var refreshed = catalog.client(config.type()).refresh(tokens.refreshToken());
    if (refreshed == null) {
      logger.log(Level.WARNING, "{0} rejected the refresh token for Organization [{1}], so the authorization must be granted again", label, organizationId);
      unlink(organizationId);
      return null;
    }

    store(config.type(), organizationId, new OAuthConnection(config.connection().login(), refreshed));
    return refreshed.accessToken();
  }

  /**
   * Builds the URL that starts a host's authorization.
   *
   * @param type        The kind of source.
   * @param redirectURI Where the host returns the browser.
   * @param state       The opaque value the callback checks against its cookie.
   * @return The absolute URL to redirect the browser to.
   */
  public String authorizeURL(BriefSourceType type, String redirectURI, String state) {
    return catalog.authorizeURL(type, redirectURI, state);
  }

  /**
   * Completes the OAuth exchange and stores the credential against the Organization's source, replacing any
   * credential already there and creating the source if the Organization has none yet — or replacing a source of
   * another kind, since an Organization holds one source.
   *
   * @param type           The kind of source being connected.
   * @param organizationId The Organization the connection was started for.
   * @param code           The authorization code the host sent to the callback.
   * @param redirectURI    The redirect URI the authorize request used.
   * @return The result, which the callback turns into a message on the page it returns to.
   */
  public LinkResult link(BriefSourceType type, UUID organizationId, String code, String redirectURI) {
    var client = catalog.client(type);
    var tokens = client.exchangeCode(code, redirectURI);
    if (tokens == null) {
      return LinkResult.EXCHANGE_FAILED;
    }

    var login = client.login(tokens.accessToken());
    if (login == null) {
      return LinkResult.EXCHANGE_FAILED;
    }

    if (!store(type, organizationId, new OAuthConnection(login, tokens))) {
      logger.log(Level.ERROR, "Unable to store the {0} credential for Organization [{1}], which no longer exists", type.label(), organizationId);
      return LinkResult.LINK_FAILED;
    }

    return LinkResult.LINKED;
  }

  /**
   * Removes this Organization's credential, if there is one, leaving the repository and the poll history in place.
   * Idempotent.
   *
   * @param organizationId The Organization.
   */
  public void unlink(UUID organizationId) {
    var source = sources.findByOrganizationId(organizationId).orElse(null);
    if (source != null && source.connected()) {
      sources.update(source.withConfig(source.config().withConnection(null), Instant.now()));
    }
  }

  /**
   * Stores an authorization as the Organization's source of the given kind. A source of that kind the Organization
   * already has keeps its repository and poll history and takes the new connection; no source, or a source of
   * another kind, becomes a source of this kind that is connected and not yet registered — the state the OAuth
   * callback produces.
   *
   * @return False if the Organization no longer exists, which is the one outcome the caller reports differently.
   */
  private boolean store(BriefSourceType type, UUID organizationId, OAuthConnection connection) {
    var now = Instant.now();
    var existing = sources.findByOrganizationId(organizationId).orElse(null);
    if (existing != null && existing.type() == type) {
      return sources.update(existing.withConfig(existing.config().withConnection(connection), now));
    }

    if (organizations.findById(organizationId).isEmpty()) {
      return false;
    }

    sources.upsert(new BriefSource(UUID.randomUUID(), organizationId, catalog.unregistered(type, connection),
        null, null, null, null, now, now));
    return true;
  }

  /**
   * What happened when an operator tried to connect an Organization to a repository host.
   */
  public enum LinkResult {
    /**
     * The host would not turn the callback's code into a token. Almost always a code that was already used or has
     * expired, which a reload of the callback URL produces on its own.
     */
    EXCHANGE_FAILED,
    /**
     * The host authorized the operator, but there was no Organization left to store the credential against — it
     * was deleted while the operator was away authorizing.
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
