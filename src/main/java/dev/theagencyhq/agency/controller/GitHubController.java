/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * The two round trips between the Agency and GitHub. The OAuth handshake: send the operator to GitHub to authorize
 * the Agency's GitHub App for an Organization, and take the authorization code back when they return. And the
 * installation: send them to GitHub to install the App on an account (or widen what an installation covers), and
 * take them back to the repository picker when they return, so the repositories they just granted are listed
 * without them having to find their way back.
 *
 * <p>Every route sits inside the gated {@code /app} prefix, so only a signed-in operator can start either trip or
 * land its return. On top of that, all of them require the caller to be an ACTIVE OWNER of the Organization — the
 * Organization travels as a query parameter here rather than a path attribute, so {@code OrganizationSecurity}
 * cannot see it and the check is made inline. Without it, the OWNER gate on the connect pages would be decoration:
 * anyone who knew an Organization's id could swap its GitHub credential for their own. The session survives the
 * round trip because the browser profile's cookies are {@code SameSite=Lax} and so ride along on a top-level
 * navigation arriving from github.com.
 *
 * <p>The state parameter is a random nonce and nothing else. The Organization the credential will be stored
 * against travels in the encrypted cookie alongside that nonce, never in the URL: a state value that carried the
 * Organization id would be a value an attacker could choose, and the whole job of the pair is to prove this
 * return answers a trip <em>this</em> browser started. Both trips share the one cookie: GitHub passes the
 * {@code state} on the install URL through to the App's setup URL exactly as it passes the OAuth one through to the
 * callback, so the same nonce does the same job in both.
 */
public class GitHubController {
  public static final String CALLBACK_PATH = "/app/oauth/github/callback";
  public static final String COOKIE_PATH = "/app/oauth";
  public static final String SETUP_PATH = "/app/oauth/github/setup";
  public static final String STATE_COOKIE = "github_oauth_state";
  private static final SecureRandom random = new SecureRandom();
  private final Cookies cookies;
  private final DatabaseService database;
  private final String installURL;
  private final GitHubLinkService links;
  private final OIDC<User> oidc;

  /**
   * @param cookies    The cookie codec, for the encrypted state cookie.
   * @param installURL GitHub's page for installing the Agency's GitHub App on an account. Built from configuration
   *                   rather than hard-coded, because the App's slug differs between a development App and the
   *                   published one.
   * @param oidc       The browser OIDC profile, for the signed-in user.
   */
  public GitHubController(Cookies cookies, String installURL, OIDC<User> oidc) {
    this.cookies = cookies;
    this.database = Services.databaseService();
    this.installURL = installURL;
    this.links = Services.gitHubLinkService();
    this.oidc = oidc;
  }

  public void callback(HTTPRequest req, HTTPResponse res) {
    var organizationId = verifiedOrganization(req, res);
    if (organizationId == null) {
      // A missing, tampered, or mismatched state leaves no Organization to send the browser back to, so the listing
      // is the only honest destination.
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
    if (result == GitHubLinkService.LinkResult.LINK_FAILED) {
      res.sendRedirect("/app/organizations/?status=" + result.name().toLowerCase(Locale.ROOT), 303);
      return;
    }

    // An App with "Request user authorization (OAuth) during installation" enabled sends installs here rather than
    // to the setup URL, with setup_action alongside the code. The authorization is genuine and has just been stored,
    // but the operator was installing, so they go where /setup would have taken them: back to the picker.
    var setupAction = req.getParameter("setup_action");
    if (result == GitHubLinkService.LinkResult.LINKED && setupAction != null) {
      res.sendRedirect(connectPath(organizationId, setupAction), 303);
      return;
    }

    res.sendRedirect(organizationPath(organizationId, result), 303);
  }

  /**
   * Sends the operator to GitHub to install the App, or to change which repositories an installation covers. The
   * same page serves both: GitHub offers the accounts the App is not yet installed on, and a configure link for
   * each one it is.
   */
  public void install(HTTPRequest req, HTTPResponse res) throws IOException {
    var id = ownedOrganization(req, res);
    if (id == null) {
      return;
    }

    res.sendRedirect(installURL + "?state=" + rememberState(req, res, id), 302);
  }

  /**
   * Where GitHub sends the browser after an installation is created or changed: the App's setup URL, which must be
   * configured on the App as exactly {@code <base URL>/app/oauth/github/setup} with "Redirect on update" enabled.
   * Returns to the picker of the Organization the trip started from, which lists the repositories afresh. GitHub
   * only uses the setup URL when the App does not request user authorization during installation; when it does,
   * the same return arrives at {@link #callback} instead, which handles it too.
   *
   * <p>GitHub sends every install of the App here, including ones begun on github.com with no picker waiting. So a
   * missing state is not an error, just a return with nowhere in particular to go: the listing.
   *
   * <p>The {@code installation_id} GitHub appends is deliberately unread. GitHub's own documentation says not to
   * trust it, and the picker lists installations with the operator's token anyway, which cannot be spoofed.
   */
  public void setup(HTTPRequest req, HTTPResponse res) {
    var organizationId = verifiedOrganization(req, res);
    if (organizationId == null || database.findOrganization(organizationId).isEmpty() ||
        !ownedByCaller(organizationId)) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    res.sendRedirect(connectPath(organizationId, req.getParameter("setup_action")), 303);
  }

  public void start(HTTPRequest req, HTTPResponse res) throws IOException {
    var id = ownedOrganization(req, res);
    if (id == null) {
      return;
    }

    res.sendRedirect(links.authorizeURL(req.getBaseURL() + CALLBACK_PATH, rememberState(req, res, id)), 302);
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * The picker to return to after an install, telling it how the install ended. {@code request} is an operator who
   * cannot install on that account themselves asking its admins to: nothing has changed yet, and the picker has to
   * say so rather than present an unchanged list as the result.
   */
  private String connectPath(UUID organizationId, String setupAction) {
    var status = "request".equals(setupAction) ? "install_requested" : "installed";
    return "/app/organizations/" + organizationId + "/connect?status=" + status;
  }

  private String organizationPath(UUID organizationId, GitHubLinkService.LinkResult result) {
    return "/app/organizations/" + organizationId + "?status=" + result.name().toLowerCase(Locale.ROOT);
  }

  private boolean ownedByCaller(UUID organizationId) {
    return database.findMember(organizationId, oidc.user().userId())
                   .filter(m -> m.state() == MembershipState.ACTIVE && m.role() == Role.OWNER)
                   .isPresent();
  }

  /**
   * The Organization named by the {@code organizationId} query parameter, resolved before the browser leaves for
   * GitHub rather than after it comes back. A state cookie naming an Organization that never existed would send the
   * operator through a whole trip only to land on a 404, having granted something for nothing.
   *
   * @return Its id, or {@code null} once the response has already been written: a 404 for an unknown Organization,
   *     or the same wordless redirect to the listing that {@code OrganizationSecurity} gives for one the caller does
   *     not own.
   */
  private UUID ownedOrganization(HTTPRequest req, HTTPResponse res) throws IOException {
    var raw = req.getParameter("organizationId");
    var id = raw == null ? null : uuid(raw);
    if (id == null || database.findOrganization(id).isEmpty()) {
      Main.missing(req, res);
      return null;
    }

    if (!ownedByCaller(id)) {
      res.sendRedirect("/app/organizations/", 303);
      return null;
    }

    return id;
  }

  /**
   * Writes the state cookie for a trip to GitHub and returns the nonce to send along with it.
   *
   * <p>The cookie carries the parsed id's canonical form, never the raw parameter. The return puts this straight
   * into the path of the URL it redirects to, and a value that merely parsed as a UUID is not the same thing as one
   * that came out of the parser -- writing the canonical form is what makes it impossible for anything the caller
   * typed to reach that path.
   */
  private String rememberState(HTTPRequest req, HTTPResponse res, UUID organizationId) {
    var nonce = new BigInteger(160, random).toString(36);
    cookies.write(STATE_COOKIE, nonce + ":" + organizationId)
           .encrypted()
           // Scoped to the routes in this class, so the state is not attached to every request the admin UI makes
           // for as long as it exists.
           .path(COOKIE_PATH)
           // Lax rather than Strict: the return arrives as a top-level navigation from github.com, and Strict would
           // withhold the cookie on exactly that request -- the one it exists for.
           .sameSite(Cookie.SameSite.Lax)
           .to(req, res);
    return nonce;
  }

  /**
   * Reads and clears the state cookie, and checks its nonce against the request's {@code state}.
   *
   * <p>Cleared before anything else can fail: one authorization code is good for one exchange, so a state left
   * behind is only ever an invitation to replay the return.
   *
   * @return The Organization the trip was started for, or {@code null} when there is no cookie, it fails its
   *     integrity check, or its nonce does not match -- a return that no start from this browser can vouch for.
   */
  private UUID verifiedOrganization(HTTPRequest req, HTTPResponse res) {
    String cookie;
    try {
      cookie = cookies.read(STATE_COOKIE).encrypted().from(req);
    } catch (CookieIntegrityException e) {
      return null;
    }

    cookies.clear(STATE_COOKIE).path(COOKIE_PATH).from(req, res);
    if (cookie == null) {
      return null;
    }

    var separator = cookie.indexOf(':');
    if (separator < 0) {
      return null;
    }

    var nonce = cookie.substring(0, separator);
    var state = req.getParameter("state");
    if (!MessageDigest.isEqual(nonce.getBytes(), state != null ? state.getBytes() : new byte[0])) {
      return null;
    }

    // The cookie was written from a parsed UUID's canonical form, so this parse only fails if the encryption key
    // leaked -- but an unparseable id must still not reach the redirect it would otherwise be pasted into.
    return uuid(cookie.substring(separator + 1));
  }
}
