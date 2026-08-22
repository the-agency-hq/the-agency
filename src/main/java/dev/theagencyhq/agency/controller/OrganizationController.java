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
import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.github.GitHubClient;
import dev.theagencyhq.agency.github.GitHubException;
import dev.theagencyhq.agency.github.GitHubUnauthorizedException;
import dev.theagencyhq.agency.model.github.GitHubRepository;
import dev.theagencyhq.agency.model.BriefFile;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.MembershipState;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.User;
import dev.theagencyhq.agency.model.view.BriefFileView;
import dev.theagencyhq.agency.model.view.BriefVersionView;
import dev.theagencyhq.agency.model.view.OrganizationConnectView;
import dev.theagencyhq.agency.model.view.OrganizationDetailView;
import dev.theagencyhq.agency.model.view.OrganizationsView;
import dev.theagencyhq.agency.security.OrganizationSecurity;
import dev.theagencyhq.agency.service.GitHubLinkService;
import dev.theagencyhq.agency.service.OrganizationService;
import dev.theagencyhq.agency.service.PollerService;
import dev.theagencyhq.agency.service.Services;

/**
 * The admin UI: create an Organization, connect it to a GitHub repository, trigger a rebuild, and inspect exactly
 * what a Brief version contains.
 *
 * <p>Naming, connecting GitHub, and picking a repository are three steps on separate pages because each needs the
 * one before it: the Organization has to exist for the OAuth callback to have somewhere to return to, and the
 * repository picker is populated from GitHub with the Organization's own authorization. The Organization's view
 * page holds the sequence together — it warns when the GitHub connection is missing or dead, carries the button
 * that starts (or restarts) the authorization, and is where the callback lands.
 *
 * <p>Every route here sits behind the browser OIDC profile installed on the {@code /app} prefix, so an
 * unauthenticated visitor is redirected to the provider and never reaches a handler. Authorization is membership:
 * {@code OrganizationSecurity} on the {@code /app/organizations} prefix admits only users with a membership row in
 * the path-bound Organization, and the management routes additionally require an ACTIVE OWNER — see {@code Main}'s
 * route table.
 */
public class OrganizationController {
  private final DatabaseService database;
  private final GitHubClient github;
  private final String installURL;
  private final GitHubLinkService links;
  private final OIDC<User> oidc;
  private final OrganizationService organizationService;
  private final PollerService poller;
  private final JTETemplates templates;

  /**
   * @param installURL Where to send an operator who has authorized the Agency's GitHub App but not yet installed it
   *                   on any account. Built from configuration rather than hard-coded, because the App's slug
   *                   differs between a development App and the published one.
   * @param oidc       The browser OIDC profile, for the signed-in user.
   * @param templates  The template engine.
   */
  public OrganizationController(String installURL, OIDC<User> oidc, JTETemplates templates) {
    this.database = Services.databaseService();
    this.github = Services.gitHubClient();
    this.installURL = installURL;
    this.links = Services.gitHubLinkService();
    this.oidc = oidc;
    this.organizationService = Services.organizationService();
    this.poller = Services.pollerService();
    this.templates = templates;
  }

  // Both the quote and the backslash have to go. Stripping only the quote leaves a name ending in a backslash to
  // escape the closing quote of filename="..." -- the header then runs on into whatever follows it, which is the
  // same header-injection shape stripping the quote was there to prevent.
  private static String filenameOf(String path) {
    var slash = path.lastIndexOf('/');
    var name = slash < 0 ? path : path.substring(slash + 1);
    return name.replace("\"", "").replace("\\", "");
  }

  private static Integer parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * The repository picker, reached from the Organization's page once its GitHub connection exists.
   */
  public void connect(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    // The picker cannot render without a working token to list repositories with, so an unconnected Organization
    // is sent back to its own page -- which is where the connection is offered -- rather than shown a dead end.
    var accessToken = links.accessToken(organization.id(), organization.gitHubConnection());
    if (accessToken == null) {
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
      return;
    }

    renderConnect(req, res, organization, accessToken, List.of(), req.getParameter("repository"),
        req.getParameter("branch"));
  }

  /**
   * Registers the repository the operator picked. Rejecting it re-renders the picker rather than redirecting, so
   * the reason is shown next to the form that produced it.
   */
  public void connectSource(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var fullName = req.getParameter("repository");
    var branch = req.getParameter("branch");
    var accessToken = links.accessToken(organization.id(), organization.gitHubConnection());
    if (accessToken == null) {
      // The authorization died between rendering the picker and submitting it. The Organization's page is where
      // the (re)connect warning lives, so the submission lands there rather than on a picker that cannot work.
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
      return;
    }

    // The form carries owner/name as one field because that is how GitHub names a repository everywhere the
    // operator has seen it, including in the select this posts from.
    var slash = fullName == null ? -1 : fullName.indexOf('/');
    if (slash <= 0 || slash == fullName.length() - 1) {
      renderConnect(req, res, organization, accessToken, List.of("A GitHub repository is required."), fullName,
          branch);
      return;
    }

    try {
      organizationService.connect(organization.id(), accessToken, fullName.substring(0, slash),
          fullName.substring(slash + 1), branch);
    } catch (GitHubUnauthorizedException e) {
      // The credential died between rendering the picker and validating the submission, and GitHub said so. It is
      // removed for the same reason as in renderConnect: the Organization's page must offer the reconnect.
      links.unlink(organization.id());
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
      return;
    } catch (ValidationException e) {
      renderConnect(req, res, organization, accessToken, e.errors(), fullName, branch);
      return;
    }

    // Straight to a build rather than waiting out an interval: an operator who has just connected a repository is
    // watching for the first version, and a minute of an empty version list reads as a failure.
    poller.nudge();
    res.sendRedirect("/app/organizations/" + organization.id(), 303);
  }

  public void create(HTTPRequest req, HTTPResponse res) throws IOException {
    var name = req.getParameter("name");
    try {
      // Straight to the Organization's page, which owns everything that happens next: it warns that GitHub is not
      // connected yet and carries the button that starts the authorization.
      var organization = organizationService.create(name, oidc.user());
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
    } catch (ValidationException e) {
      renderForm(req, res, e.errors(), name == null ? "" : name);
    }
  }

  public void detail(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var source = database.findSource(organization.id()).orElse(null);
    var versions = database.listBriefs(organization.id());
    // Cached by OrganizationSecurity, which admits no request without one, so this is a read rather than a query.
    var membership = (Member) req.getAttribute(OrganizationSecurity.MEMBER_ATTRIBUTE);
    render("pages/detail.jte", req, res,
        new OrganizationDetailView(organization, source, versions, membership, req.getParameter("status")));
  }

  public void file(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var version = parseInt((String) req.getAttribute("version"));
    var index = parseInt((String) req.getAttribute("index"));
    if (version == null || index == null) {
      Main.missing(req, res);
      return;
    }

    var brief = database.findBrief(organization.id(), version).orElse(null);
    if (brief == null) {
      Main.missing(req, res);
      return;
    }

    var files = brief.files();
    if (index < 0 || index >= files.size()) {
      Main.missing(req, res);
      return;
    }

    var file = files.get(index);
    var text = !BriefFile.ENCODING_BASE64.equals(file.encoding());
    var bytes = text ? file.content().getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(file.content());

    if ("true".equals(req.getParameter("download"))) {
      res.setHeader("Content-Disposition", "attachment; filename=\"" + filenameOf(file.path()) + "\"");
      res.setContentType("application/octet-stream");
      res.setContentLength(bytes.length);
      res.getOutputStream().write(bytes);
      return;
    }

    render("pages/file.jte", req, res, new BriefFileView(organization, version, file, text, bytes.length));
  }

  public void list(HTTPRequest req, HTTPResponse res) throws IOException {
    var sourcesByOrganization = database.listSources()
                                        .stream()
                                        .collect(Collectors.toMap(BriefSource::organizationId, s -> s));
    // Versions only, never latestBriefs(): this page renders one integer per Organization, and latestBriefs()
    // carries every Brief's full document with it.
    var latestVersions = database.latestBriefVersions();

    // The viewer's Organizations, not all of them: membership is what makes one visible here. PENDING rows are
    // deliberately included, because this listing is how an invited user finds the Organization to accept -- but
    // they render as invitations above the listing, with Accept and Decline, rather than as rows in it. An
    // Organization the viewer has not joined yet has no status worth a table row.
    var userId = oidc.user().userId();
    var membershipsByOrganization = database.listMembersForUser(userId)
                                            .stream()
                                            .collect(Collectors.toMap(Member::organizationId, m -> m));

    var invitations = new ArrayList<OrganizationsView.Invitation>();
    var rows = new ArrayList<OrganizationsView.Row>();
    for (var o : database.listOrganizationsForUser(userId)) {
      var membership = membershipsByOrganization.get(o.id());
      if (membership.state() == MembershipState.PENDING) {
        invitations.add(new OrganizationsView.Invitation(o.id(), o.name(), membership.role()));
        continue;
      }

      var source = sourcesByOrganization.get(o.id());
      rows.add(new OrganizationsView.Row(
          o.id(),
          o.name(),
          source == null ? "" : source.fullName(),
          source == null ? "" : source.branch(),
          source == null ? null : source.lastStatus(),
          source == null ? null : source.lastError(),
          latestVersions.get(o.id()),
          source == null ? null : source.lastPolledInstant()));
    }

    render("pages/organizations.jte", req, res,
        new OrganizationsView(req.getParameter("status"), invitations, rows));
  }

  public void newForm(HTTPRequest req, HTTPResponse res) throws IOException {
    renderForm(req, res, List.of(), "");
  }

  public void rebuild(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    // A nudge, not a build. Running the fetch and the build on the request thread would hold an HTTP worker for as
    // long as the slowest repository download takes, and it is the only thing that would ever build an Organization
    // off the poller thread -- which is what forced the per-Organization lock this class used to depend on. The
    // cycle is where the result appears, so the detail page reports the source's status whenever the admin next
    // loads it.
    poller.nudge();
    res.sendRedirect("/app/organizations/" + organization.id(), 303);
  }

  public void version(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var version = parseInt((String) req.getAttribute("version"));
    if (version == null) {
      Main.missing(req, res);
      return;
    }

    var brief = database.findBrief(organization.id(), version).orElse(null);
    if (brief == null) {
      Main.missing(req, res);
      return;
    }

    var files = brief.files();
    var entries = new ArrayList<BriefVersionView.Entry>(files.size());
    for (var i = 0; i < files.size(); i++) {
      var file = files.get(i);
      var size = BriefFile.ENCODING_BASE64.equals(file.encoding())
          ? Base64.getDecoder().decode(file.content()).length
          : file.content().getBytes(StandardCharsets.UTF_8).length;
      entries.add(new BriefVersionView.Entry(i, file.path(), file.encoding(), file.mode(), size, file.missionTypes()));
    }

    var view = new BriefVersionView(organization, brief.version(), brief.checksum(), brief.sourceCommit(),
        brief.insertInstant(), entries);
    render("pages/version.jte", req, res, view);
  }

  private Organization findOrganization(HTTPRequest req) {
    var raw = (String) req.getAttribute("organizationId");
    if (raw == null) {
      return null;
    }

    UUID id;
    try {
      id = UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }

    return database.findOrganization(id).orElse(null);
  }

  /**
   * Renders a page with its own view model plus the signed-in user, which the shared layout needs on every page to
   * draw the chrome. Going through one helper is what keeps that from being a thing each handler has to remember:
   * forgetting it is not a compile error, it is a template that fails to render at runtime.
   *
   * @param template The template name.
   * @param req      The request.
   * @param res      The response.
   * @param model    The page's own view model, bound as {@code model}.
   * @throws IOException If the response cannot be written.
   */
  private void render(String template, HTTPRequest req, HTTPResponse res, Object model) throws IOException {
    templates.html(template, req, res, Map.of("model", model, "viewer", oidc.user()));
  }

  /**
   * Renders the repository picker, asking GitHub for the repositories the Organization's credential can reach.
   *
   * <p>A GitHub failure while listing degrades to the Organization's page rather than to an error page: a picker
   * with no list is a dead end, an outage on GitHub's side is not a reason to make the Organization unreachable,
   * and if the failure was the credential dying, that page is where the (re)connect warning lives.
   */
  private void renderConnect(HTTPRequest req, HTTPResponse res, Organization organization, String accessToken,
                             List<String> errors, String selected, String branch) throws IOException {
    var repositories = new ArrayList<String>();
    var defaultBranches = new HashMap<String, String>();
    try {
      for (var repository : repositories(accessToken)) {
        repositories.add(repository.fullName());
        defaultBranches.put(repository.fullName(), repository.defaultBranch() == null ? "main" : repository.defaultBranch());
      }
    } catch (GitHubUnauthorizedException e) {
      // GitHub refused the credential itself, which no retry fixes: it is removed here so the page this lands on
      // stops reading the Organization as connected and shows the reconnect warning instead of this same picker.
      links.unlink(organization.id());
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
      return;
    } catch (GitHubException e) {
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
      return;
    }

    Collections.sort(repositories);
    var source = database.findSource(organization.id()).orElse(null);
    var view = new OrganizationConnectView(organization, source, repositories, defaultBranches, installURL, errors,
        selected == null ? "" : selected, branch == null ? "" : branch);
    render("pages/connect.jte", req, res, view);
  }

  private void renderForm(HTTPRequest req, HTTPResponse res, List<String> errors, String name) throws IOException {
    res.setStatus(200);
    // Not render(): this page's parameters are two separate values rather than one view model, so its map is built
    // here. The viewer still has to be in it, for the same layout.
    templates.html("pages/new.jte", req, res, Map.of("errors", errors, "name", name, "viewer", oidc.user()));
  }

  /**
   * Every repository the operator can offer, flattened across every installation of the Agency's GitHub App they
   * can reach. Two levels rather than one because that is how a GitHub App grants access: the App is installed on
   * an account, and each installation covers the repositories that account chose to give it.
   */
  private List<GitHubRepository> repositories(String accessToken) {
    var installations = github.installations(accessToken);
    if (installations == null) {
      return List.of();
    }

    var all = new ArrayList<GitHubRepository>();
    for (var installation : installations) {
      var repositories = github.repositories(accessToken, installation.id());
      if (repositories != null) {
        all.addAll(repositories);
      }
    }

    return all;
  }
}
