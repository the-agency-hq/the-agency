/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.Main;
import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.MembershipState;
import dev.theagencyhq.agency.model.Role;
import dev.theagencyhq.agency.model.User;
import dev.theagencyhq.agency.service.GitHubLinkService;
import dev.theagencyhq.agency.service.Services;

/**
 * The OAuth handshake between the Agency and GitHub: send the operator to GitHub to authorize the Agency's GitHub
 * App for an Organization, and take the authorization code back when they return.
 *
 * <p>Both routes sit inside the gated {@code /app} prefix, so only a signed-in operator can start a connection or
 * land a callback. On top of that, both require the caller to be an ACTIVE OWNER of the Organization — the
 * Organization travels as a query parameter here rather than a path attribute, so {@code OrganizationSecurity}
 * cannot see it and the check is made inline. Without it, the OWNER gate on the connect pages would be decoration:
 * anyone who knew an Organization's id could swap its GitHub credential for their own. The session survives the
 * round trip because the browser profile's cookies are {@code SameSite=Lax} and so ride along on a top-level
 * navigation arriving from github.com.
 *
 * <p>The state parameter is a random nonce and nothing else. The Organization the credential will be stored
 * against travels in the encrypted cookie alongside that nonce, never in the URL: a state value that carried the
 * Organization id would be a value an attacker could choose, and the whole job of the pair is to prove this
 * callback answers a connection <em>this</em> browser started.
 */
public class GitHubController {
  public static final String CALLBACK_PATH = "/app/oauth/github/callback";
  public static final String COOKIE_PATH = "/app/oauth";
  public static final String STATE_COOKIE = "github_oauth_state";
  private static final SecureRandom random = new SecureRandom();
  private final Cookies cookies;
  private final DatabaseService database;
  private final GitHubLinkService links;
  private final OIDC<User> oidc;

  public GitHubController(Cookies cookies, OIDC<User> oidc) {
    this.cookies = cookies;
    this.database = Services.databaseService();
    this.links = Services.gitHubLinkService();
    this.oidc = oidc;
  }

  public void callback(HTTPRequest req, HTTPResponse res) {
    String cookie;
    try {
      cookie = cookies.read(STATE_COOKIE).encrypted().from(req);
    } catch (CookieIntegrityException e) {
      // A cookie that fails its integrity check is a tampered cookie, so there is no Organization to send the
      // browser back to and the listing is the only honest destination.
      res.sendRedirect("/app/organizations/?status=" + GitHubLinkService.LinkResult.STATE_MISMATCH.name().toLowerCase(Locale.ROOT), 303);
      return;
    }

    // Cleared before anything else can fail: one authorization code is good for one exchange, so a state left
    // behind is only ever an invitation to replay this callback.
    cookies.clear(STATE_COOKIE).path(COOKIE_PATH).from(req, res);

    // Cookie is missing completely, bad nonce
    if (cookie == null) {
      res.sendRedirect("/app/organizations/?status=" + GitHubLinkService.LinkResult.STATE_MISMATCH.name().toLowerCase(Locale.ROOT), 303);
      return;
    }

    // Cookie is malformed, bad nonce
    var separator = cookie.indexOf(':');
    if (separator < 0) {
      res.sendRedirect("/app/organizations/?status=" + GitHubLinkService.LinkResult.STATE_MISMATCH.name().toLowerCase(Locale.ROOT), 303);
      return;
    }

    // Check the nonce from the cookie and from the OAuth redirect state
    String nonce = cookie.substring(0, separator);
    String state = req.getParameter("state");
    if (!MessageDigest.isEqual(nonce.getBytes(), state != null ? state.getBytes() : new byte[0])) {
      res.sendRedirect("/app/organizations/?status=" + GitHubLinkService.LinkResult.STATE_MISMATCH.name().toLowerCase(Locale.ROOT), 303);
      return;
    }

    // The cookie was written from a parsed UUID's canonical form, so this parse only fails if the encryption key
    // leaked -- but an unparseable id must still not reach the redirect it would otherwise be pasted into.
    var organizationId = uuid(cookie.substring(separator + 1));
    if (organizationId == null) {
      res.sendRedirect("/app/organizations/?status=" + GitHubLinkService.LinkResult.STATE_MISMATCH.name().toLowerCase(Locale.ROOT), 303);
      return;
    }

    // Re-checked here, not just in start: the state cookie proves the callback answers a start from this browser,
    // but the membership could have been revoked while the operator was away authorizing. Only for an Organization
    // that still exists, though — a deleted one has no members to check, and it must keep reaching links.link so
    // the operator gets the honest LINK_FAILED outcome rather than a wordless bounce.
    if (database.findOrganization(organizationId).isPresent() && !ownedByCaller(organizationId)) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    var code = req.getParameter("code");
    if (code == null) {
      // GitHub returns here with `error` instead of `code` when the operator declines the authorization. Not a
      // failure of ours, and the Organization's page already reads as "not connected", so it says so there.
      res.sendRedirect(organizationPath(organizationId, GitHubLinkService.LinkResult.EXCHANGE_FAILED), 303);
      return;
    }

    // Every outcome returns to the Organization's page -- except LINK_FAILED, which means the Organization was
    // deleted while the operator was away authorizing, so its page is a 404 and the listing is the only honest
    // destination left.
    var result = links.link(organizationId, code, req.getBaseURL() + CALLBACK_PATH);
    res.sendRedirect(result == GitHubLinkService.LinkResult.LINK_FAILED
        ? "/app/organizations/?status=" + result.name().toLowerCase(Locale.ROOT)
        : organizationPath(organizationId, result), 303);
  }

  public void start(HTTPRequest req, HTTPResponse res) throws IOException {
    // The Organization is resolved before the browser leaves for GitHub, not after it comes back. A state cookie
    // naming an Organization that never existed would send the operator through a whole authorization only to land
    // on a 404, having granted an authorization for nothing.
    var raw = req.getParameter("organizationId");
    var id = raw == null ? null : uuid(raw);
    if (id == null || database.findOrganization(id).isEmpty()) {
      Main.missing(req, res);
      return;
    }

    // The same denial OrganizationSecurity gives: back to the listing, saying nothing about why.
    if (!ownedByCaller(id)) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    // The parsed id's canonical form, never the raw parameter. The callback puts this straight into the path of
    // the URL it redirects to, and a value that merely parsed as a UUID is not the same thing as one that came out
    // of the parser -- writing the canonical form is what makes it impossible for anything the caller typed to
    // reach that path.
    var nonce = new BigInteger(160, random).toString(36);
    cookies.write(STATE_COOKIE, nonce + ":" + id)
           .encrypted()
           // Scoped to the two routes in this class, so the state is not attached to every request the admin UI
           // makes for as long as it exists.
           .path(COOKIE_PATH)
           // Lax rather than Strict: the callback arrives as a top-level navigation from github.com, and Strict
           // would withhold the cookie on exactly that request -- the one it exists for.
           .sameSite(Cookie.SameSite.Lax)
           .to(req, res);

    res.sendRedirect(links.authorizeURL(req.getBaseURL() + CALLBACK_PATH, nonce), 302);
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private String organizationPath(UUID organizationId, GitHubLinkService.LinkResult result) {
    return "/app/organizations/" + organizationId + "?status=" + result.name().toLowerCase(Locale.ROOT);
  }

  private boolean ownedByCaller(UUID organizationId) {
    return database.findMember(organizationId, oidc.user().userId())
                   .filter(m -> m.state() == MembershipState.ACTIVE && m.role() == Role.OWNER)
                   .isPresent();
  }
}
