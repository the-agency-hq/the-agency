/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module dev.theagencyhq.agency;
import module io.avaje.inject;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import org.lattejava.web.Configuration;

/**
 * Connecting a repository host as an Organization's Brief source: every {@code /oauth/{kind}} and
 * {@code /sources/{kind}} route, for every kind, from the OAuth handshake that creates the source to the picker that
 * points it at a repository. One controller for GitHub and GitLab alike, told which it is serving by the route table —
 * the handshake, the cookie, the ownership checks, and the picker are the same for both, and only the host behind
 * {@code SourceLinkService} differs.
 *
 * <p>Two round trips to the host and one page. The OAuth handshake ({@code /app/oauth/{kind}/start} and
 * {@code /callback}): send the operator to the host to authorize the Agency for an Organization, take the authorization
 * code back when they return, and store the credential — which is what creates the Organization's source — then land on
 * the Sources page, where the connection is offered and reported. And the picker itself
 * ({@code /app/organizations/{id}/sources/{kind}}): list every repository the source's credential can reach, and
 * register the one the operator picks.
 *
 * <p>GitHub alone has a third trip, the installation ({@code /app/oauth/github/install} and {@code /setup}): a
 * GitHub App reads only the repositories it has been installed on, so the picker sends the operator to GitHub to
 * install it on an account (or widen what an installation covers) and takes them back to the picker when they return,
 * so the repositories they just granted are listed without them having to find their way back. A GitLab application
 * reads whatever the authorizing account can, so GitLab has no such step and no such routes.
 *
 * <p>Every route sits inside the gated {@code /app} prefix, so only a signed-in operator can start either trip, land
 * its return, or reach the picker. On top of that, all of them require the caller to be an ACTIVE OWNER of the
 * Organization. The picker routes get that from {@code OrganizationSecurity} and the {@code HasRole} gate in
 * {@code Main}'s route table; the OAuth routes carry the Organization as a query parameter rather than a path
 * attribute, so {@code OrganizationSecurity} cannot see it and the check is made inline. Without it, the OWNER gate on
 * the picker would be decoration: anyone who knew an Organization's id could swap its credential for their own. The
 * session survives the round trip because the browser profile's cookies are {@code SameSite=Lax} and so ride along on a
 * top-level navigation arriving from the host. A kind this server has no credentials for is a 404 on every one of these
 * routes, exactly as its card is absent from the Sources page.
 *
 * <p>The state parameter is a random nonce and nothing else. The Organization the credential will be stored
 * against, and the kind of source the trip is for, travel in the encrypted cookie alongside that nonce, never in the
 * URL: a state value that carried the Organization id would be a value an attacker could choose, and the whole job of
 * the pair is to prove this return answers a trip <em>this</em> browser started — for this host. All three trips share
 * the one cookie: GitHub passes the {@code state} on the install URL through to the App's setup URL exactly as both
 * hosts pass the OAuth one through to the callback, so the same nonce does the same job in each.
 */
@Prototype
public class RepositorySourceController {
  public static final String COOKIE_PATH = "/app/oauth";
  public static final String SETUP_PATH = "/app/oauth/github/setup";
  public static final String STATE_COOKIE = "oauth_state";
  private static final SecureRandom random = new SecureRandom();
  private final SourceCatalog catalog;
  private final Cookies cookies;
  private final Flash flash;
  private final String installURL;
  private final SourceLinkService links;
  private final MemberRepository members;
  private final Messages messages;
  private final OIDC<User> oidc;
  private final OrganizationService organizationService;
  private final OrganizationRepository organizations;
  private final PollerService poller;
  private final BriefSourceRepository sources;
  private final JTETemplates templates;

  /**
   * @param config The configuration, for the GitHub App's slug: the page for installing the App on an account is built
   *               from it rather than hard-coded, because the slug differs between a development App and the published
   *               one.
   */
  public RepositorySourceController(Configuration config, SourceCatalog catalog, Cookies cookies,
                                    SourceLinkService links, MemberRepository members,
                                    @Named(Wiring.SSR) OIDC<User> oidc, OrganizationRepository organizations,
                                    OrganizationService organizationService, PollerService poller,
                                    BriefSourceRepository sources, JTETemplates templates,
                                    HTTPRequest request) {
    this.catalog = catalog;
    this.cookies = cookies;
    this.flash = new Flash(request);
    this.installURL = "https://github.com/apps/" + config.get("github.appName", "") + "/installations/new";
    this.links = links;
    this.members = members;
    this.messages = new Messages(request);
    this.oidc = oidc;
    this.organizations = organizations;
    this.organizationService = organizationService;
    this.poller = poller;
    this.sources = sources;
    this.templates = templates;
  }

  /**
   * @param type The kind of source.
   * @return Where its host returns the browser after the authorization: the redirect URI registered with the host.
   */
  public static String callbackPath(BriefSourceType type) {
    return "/app/oauth/" + type.slug() + "/callback";
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public void callback(BriefSourceType type, HTTPRequest req, HTTPResponse res) throws IOException {
    if (!catalog.configured(type)) {
      Main.missing(req, res);
      return;
    }

    var organizationId = verifiedOrganization(type, req, res);
    if (organizationId == null) {
      // A missing, tampered, or mismatched state leaves no Organization to send the browser back to, so the listing
      // is the only honest destination.
      res.sendRedirect(listingPath(SourceLinkService.LinkResult.STATE_MISMATCH, type), 303);
      return;
    }

    // Re-checked here, not just in start: the state cookie proves the callback answers a start from this browser,
    // but the membership could have been revoked while the operator was away authorizing. Only for an Organization
    // that still exists, though — a deleted one has no members to check, and it must keep reaching links.link so
    // the operator gets the honest LINK_FAILED outcome rather than a wordless bounce.
    if (organizations.findById(organizationId).isPresent() && notOwnedByCaller(organizationId)) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    var code = req.getParameter("code");
    if (code == null) {
      // The host returns here with `error` instead of `code` when the operator declines the authorization. Not a
      // failure of ours, and the Sources page already reads as "not connected", so it says so there.
      res.sendRedirect(sourcesPath(organizationId, type, SourceLinkService.LinkResult.EXCHANGE_FAILED), 303);
      return;
    }

    // Every outcome returns to the Sources page -- except LINK_FAILED, which means the Organization was deleted
    // while the operator was away authorizing, so its pages are 404s and the listing is the only honest destination
    // left.
    var result = links.link(type, organizationId, code, req.getBaseURL() + callbackPath(type));
    if (result == SourceLinkService.LinkResult.LINK_FAILED) {
      res.sendRedirect(listingPath(result, type), 303);
      return;
    }

    if (result == SourceLinkService.LinkResult.LINKED) {
      // The page this returns to reads the connection off the source row, so it is right at once. The source's
      // status, badge, and last error are the poller's to update, and would otherwise say NOT_CONNECTED for a full
      // interval.
      poller.nudge();
    }

    // A GitHub App with "Request user authorization (OAuth) during installation" enabled sends installs here rather
    // than to the setup URL, with setup_action alongside the code. The authorization is genuine and has just been
    // stored, but the operator was installing, so they go where /setup would have taken them: back to the picker.
    var setupAction = req.getParameter("setup_action");
    if (type == BriefSourceType.GITHUB && result == SourceLinkService.LinkResult.LINKED && setupAction != null) {
      res.sendRedirect(pickerPath(organizationId, setupAction), 303);
      return;
    }

    res.sendRedirect(sourcesPath(organizationId, type, result), 303);
  }

  /**
   * Registers the repository the operator picked. Rejecting it re-renders the picker rather than redirecting, so the
   * reason is shown next to the form that produced it.
   */
  public void connectRepository(BriefSourceType type, HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(type, req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var fullName = req.getParameter("repository");
    var branch = req.getParameter("branch");
    var config = config(organization, type);
    var accessToken = links.accessToken(organization.id(), config);
    if (accessToken == null) {
      // The authorization died between rendering the picker and submitting it. The Sources page is where the
      // (re)connect warning lives, so the submission lands there rather than on a picker that cannot work.
      res.sendRedirect(sourcesPath(organization.id()), 303);
      return;
    }

    BriefSource registered;
    try {
      registered = organizationService.connect(type, organization.id(), accessToken, fullName, branch);
    } catch (RepositoryUnauthorizedException e) {
      // The credential died between rendering the picker and validating the submission, and the host said so. It
      // is removed for the same reason as in renderPicker: the Sources page must offer the reconnect.
      links.unlink(organization.id());
      res.sendRedirect(sourcesPath(organization.id()), 303);
      return;
    } catch (ValidationException e) {
      renderPicker(type, req, res, organization, config, accessToken, e.errors(), fullName, branch);
      return;
    }

    // The source vanished between resolving the token and registering the repository. The Sources page reads the
    // row as it is now, and offers whatever the row now needs.
    if (registered == null) {
      res.sendRedirect(sourcesPath(organization.id()), 303);
      return;
    }

    // Straight to a build rather than waiting out an interval: an operator who has just connected a repository is
    // watching for the first version, and a minute of an empty version list reads as a failure.
    poller.nudge();
    res.sendRedirect("/app/organizations/" + organization.id(), 303);
  }

  /**
   * Sends the operator to GitHub to install the App, or to change which repositories an installation covers. The same
   * page serves both: GitHub offers the accounts the App is not yet installed on, and a configure link for each one it
   * is.
   */
  public void install(HTTPRequest req, HTTPResponse res) throws IOException {
    var id = ownedOrganization(BriefSourceType.GITHUB, req, res);
    if (id == null) {
      return;
    }

    res.sendRedirect(installURL + "?state=" + rememberState(req, res, BriefSourceType.GITHUB, id), 302);
  }

  /**
   * The repository picker, reached from the Sources page once the connection exists.
   */
  public void repositoryForm(BriefSourceType type, HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(type, req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    // The picker cannot render without a working token to list repositories with, so an unconnected Organization
    // is sent back to its Sources page -- which is where the connection is offered -- rather than shown a dead end.
    var config = config(organization, type);
    var accessToken = links.accessToken(organization.id(), config);
    if (accessToken == null) {
      res.sendRedirect(sourcesPath(organization.id()), 303);
      return;
    }

    renderPicker(type, req, res, organization, config, accessToken, List.of(), req.getParameter("repository"),
        req.getParameter("branch"));
  }

  /**
   * Where GitHub sends the browser after an installation is created or changed: the App's setup URL, which must be
   * configured on the App as exactly {@code <base URL>/app/oauth/github/setup} with "Redirect on update" enabled.
   * Returns to the picker of the Organization the trip started from, which lists the repositories afresh. GitHub only
   * uses the setup URL when the App does not request user authorization during installation; when it does, the same
   * return arrives at {@link #callback} instead, which handles it too.
   *
   * <p>GitHub sends every install of the App here, including ones begun on github.com with no picker waiting. So a
   * missing state is not an error, just a return with nowhere in particular to go: the listing.
   *
   * <p>The {@code installation_id} GitHub appends is deliberately unread. GitHub's own documentation says not to
   * trust it, and the picker lists installations with the operator's token anyway, which cannot be spoofed.
   */
  public void setup(HTTPRequest req, HTTPResponse res) throws IOException {
    if (!catalog.configured(BriefSourceType.GITHUB)) {
      Main.missing(req, res);
      return;
    }

    var organizationId = verifiedOrganization(BriefSourceType.GITHUB, req, res);
    if (organizationId == null || organizations.findById(organizationId).isEmpty() ||
        notOwnedByCaller(organizationId)) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    res.sendRedirect(pickerPath(organizationId, req.getParameter("setup_action")), 303);
  }

  public void start(BriefSourceType type, HTTPRequest req, HTTPResponse res) throws IOException {
    var id = ownedOrganization(type, req, res);
    if (id == null) {
      return;
    }

    res.sendRedirect(links.authorizeURL(type, req.getBaseURL() + callbackPath(type), rememberState(req, res, type, id)), 302);
  }

  /**
   * @param organization The Organization.
   * @param type         The kind of source the route is for.
   * @return Its source's configuration, or {@code null} if its source is of another kind — or it has none.
   */
  private BriefSourceConfig config(Organization organization, BriefSourceType type) {
    return sources.findByOrganizationId(organization.id())
                  .map(BriefSource::config)
                  .filter(config -> config.type() == type)
                  .orElse(null);
  }

  /**
   * The Organization named by the {@code organizationId} path attribute of a picker route, or {@code null} if the
   * attribute is missing, malformed, names no Organization — or the kind is not one this server offers.
   */
  private Organization findOrganization(BriefSourceType type, HTTPRequest req) {
    if (!catalog.configured(type)) {
      return null;
    }

    var raw = (String) req.getAttribute("organizationId");
    var id = raw == null ? null : uuid(raw);
    return id == null ? null : organizations.findById(id).orElse(null);
  }

  private void flashLinkResult(SourceLinkService.LinkResult result, BriefSourceType type) {
    var messageType = result == SourceLinkService.LinkResult.LINKED ? "success" : "warning";
    String key = result.name().toLowerCase(Locale.ROOT);
    flash.addMessage(messageType, messages.get(key, type.label()));
  }

  /**
   * The listing, with the outcome queued for it to show.
   */
  private String listingPath(SourceLinkService.LinkResult result, BriefSourceType type) {
    flashLinkResult(result, type);
    return "/app/organizations/";
  }

  private boolean notOwnedByCaller(UUID organizationId) {
    return members.findByOrganizationIdAndUserId(organizationId, oidc.user().userId())
                  .filter(m -> m.state() == MembershipState.ACTIVE && m.role() == Role.OWNER)
                  .isEmpty();
  }

  /**
   * The Organization named by the {@code organizationId} query parameter, resolved before the browser leaves for the
   * host rather than after it comes back. A state cookie naming an Organization that never existed would send the
   * operator through a whole trip only to land on a 404, having granted something for nothing.
   *
   * @return Its id, or {@code null} once the response has already been written: a 404 for an unknown Organization or a
   *     kind this server does not offer, or the same wordless redirect to the listing that {@code OrganizationSecurity}
   *     gives for an Organization the caller does not own.
   */
  private UUID ownedOrganization(BriefSourceType type, HTTPRequest req, HTTPResponse res) throws IOException {
    var raw = req.getParameter("organizationId");
    var id = raw == null ? null : uuid(raw);
    if (!catalog.configured(type) || id == null || organizations.findById(id).isEmpty()) {
      Main.missing(req, res);
      return null;
    }

    if (notOwnedByCaller(id)) {
      res.sendRedirect("/app/organizations/", 303);
      return null;
    }

    return id;
  }

  /**
   * The GitHub picker to return to after an install, with how the install ended queued for it to show. {@code request}
   * is an operator who cannot install on that account themselves asking its admins to: nothing has changed yet, and the
   * picker has to say so rather than present an unchanged list as the result.
   */
  private String pickerPath(UUID organizationId, String setupAction) {
    if ("request".equals(setupAction)) {
      flash.addMessage("info", messages.get("install_requested", BriefSourceType.GITHUB.label()));
    } else {
      flash.addMessage("success", messages.get("installed", BriefSourceType.GITHUB.label()));
    }
    return "/app/organizations/" + organizationId + "/sources/github";
  }

  /**
   * Writes the state cookie for a trip to a host and returns the nonce to send along with it.
   *
   * <p>The cookie carries the parsed id's canonical form, never the raw parameter. The return puts this straight
   * into the path of the URL it redirects to, and a value that merely parsed as a UUID is not the same thing as one
   * that came out of the parser -- writing the canonical form is what makes it impossible for anything the caller typed
   * to reach that path. The kind rides along so that a return from one host cannot complete a trip started for
   * another.
   */
  private String rememberState(HTTPRequest req, HTTPResponse res, BriefSourceType type, UUID organizationId) {
    var nonce = new BigInteger(160, random).toString(36);
    cookies.write(STATE_COOKIE, nonce + ":" + type.slug() + ":" + organizationId)
           .encrypted()
           // Scoped to the OAuth routes, so the state is not attached to every request the admin UI makes for as
           // long as it exists.
           .path(COOKIE_PATH)
           // Lax rather than Strict: the return arrives as a top-level navigation from the host, and Strict would
           // withhold the cookie on exactly that request -- the one it exists for.
           .sameSite(Cookie.SameSite.Lax)
           .to(req, res);
    return nonce;
  }

  /**
   * Renders the repository picker, asking the host for the repositories the source's credential can reach.
   *
   * <p>A failure while listing degrades to the Sources page rather than to an error page: a picker with no list is
   * a dead end, an outage on the host's side is not a reason to make the Organization unreachable, and if the failure
   * was the credential dying, that page is where the (re)connect warning lives.
   */
  private void renderPicker(BriefSourceType type, HTTPRequest req, HTTPResponse res, Organization organization,
                            BriefSourceConfig config, String accessToken, List<String> errors, String selected,
                            String branch) throws IOException {
    var repositories = new ArrayList<String>();
    var defaultBranches = new HashMap<String, String>();
    try {
      for (var repository : catalog.client(type).repositories(accessToken)) {
        repositories.add(repository.fullName());
        defaultBranches.put(repository.fullName(), repository.defaultBranch() == null ? "main" : repository.defaultBranch());
      }
    } catch (RepositoryUnauthorizedException e) {
      // The host refused the credential itself, which no retry fixes: it is removed here so the page this lands on
      // stops reading the Organization as connected and shows the reconnect warning instead of this same picker.
      links.unlink(organization.id());
      res.sendRedirect(sourcesPath(organization.id()), 303);
      return;
    } catch (RepositoryException e) {
      res.sendRedirect(sourcesPath(organization.id()), 303);
      return;
    }

    Collections.sort(repositories);
    var view = new RepositorySourceView(type, organization, config, repositories, defaultBranches, errors,
        selected == null ? "" : selected, branch == null ? "" : branch);
    // The viewer rides along with the view model, which the shared layout needs on every page to draw the chrome.
    templates.html("pages/repository.jte", req, res, Map.of("model", view, "viewer", oidc.user()));
  }

  private String sourcesPath(UUID organizationId) {
    return "/app/organizations/" + organizationId + "/sources";
  }

  /**
   * The Sources page, with the outcome queued for it to show.
   */
  private String sourcesPath(UUID organizationId, BriefSourceType type, SourceLinkService.LinkResult result) {
    flashLinkResult(result, type);
    return sourcesPath(organizationId);
  }

  /**
   * Reads and clears the state cookie, and checks its nonce against the request's {@code state} and its kind against
   * the route's.
   *
   * <p>Cleared before anything else can fail: one authorization code is good for one exchange, so a state left
   * behind is only ever an invitation to replay the return.
   *
   * @return The Organization the trip was started for, or {@code null} when there is no cookie, it fails its integrity
   *     check, its nonce does not match, or it was written for another kind of source -- a return that no start from
   *     this browser can vouch for.
   */
  private UUID verifiedOrganization(BriefSourceType type, HTTPRequest req, HTTPResponse res) {
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

    var parts = cookie.split(":", 3);
    if (parts.length != 3 || !parts[1].equals(type.slug())) {
      return null;
    }

    var state = req.getParameter("state");
    if (!MessageDigest.isEqual(parts[0].getBytes(), state != null ? state.getBytes() : new byte[0])) {
      return null;
    }

    // The cookie was written from a parsed UUID's canonical form, so this parse only fails if the encryption key
    // leaked -- but an unparseable id must still not reach the redirect it would otherwise be pasted into.
    return uuid(parts[2]);
  }
}
