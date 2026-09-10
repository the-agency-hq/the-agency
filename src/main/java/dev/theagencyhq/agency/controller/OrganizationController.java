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

import dev.theagencyhq.agency.model.Member;

/**
 * The admin UI for Organizations: create one, choose its Agents, see its Brief source and version history, trigger
 * a rebuild, and inspect exactly what a Brief version contains.
 *
 * <p>The Sources page is here too — it lists every kind of source this server offers, warns when the connection is
 * missing or dead, and is where the OAuth callbacks land — but the kinds' own routes belong to
 * {@code RepositorySourceController}, which owns the handshakes and the repository picker. The Organization's own
 * page warns when there is no connected source, and points an Owner at the Sources page.
 *
 * <p>Every route here sits behind the browser OIDC profile installed on the {@code /app} prefix, so an
 * unauthenticated visitor is redirected to the provider and never reaches a handler. Authorization is membership:
 * {@code OrganizationSecurity} on the {@code /app/organizations} prefix admits only users with a membership row in
 * the path-bound Organization, and the management routes additionally require an ACTIVE OWNER — see {@code Main}'s
 * route table.
 */
@Prototype
public class OrganizationController {
  private final BriefRepository briefs;
  private final SourceCatalog catalog;
  private final MemberRepository members;
  private final OIDC<User> oidc;
  private final OrganizationRepository organizations;
  private final OrganizationService organizationService;
  private final PollerService poller;
  private final BriefSourceRepository sources;
  private final JTETemplates templates;

  public OrganizationController(BriefRepository briefs, SourceCatalog catalog, MemberRepository members,
                                @Named(Wiring.SSR) OIDC<User> oidc, OrganizationRepository organizations,
                                OrganizationService organizationService, PollerService poller,
                                BriefSourceRepository sources, JTETemplates templates) {
    this.briefs = briefs;
    this.catalog = catalog;
    this.members = members;
    this.oidc = oidc;
    this.organizations = organizations;
    this.organizationService = organizationService;
    this.poller = poller;
    this.sources = sources;
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
   * The Agent selection form. Renders the stored selection: All when the Organization has never narrowed it,
   * otherwise the Agents it picked.
   */
  public void agentsForm(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    render("pages/agents.jte", req, res, new OrganizationAgentsView(organization, organization.agents() == null,
        organization.agents() == null ? List.of() : organization.agents().enabled(), List.of()));
  }

  public void create(HTTPRequest req, HTTPResponse res) throws IOException {
    var name = req.getParameter("name");
    try {
      // Straight to the Organization's page, which warns that no source is connected yet and points the Owner at
      // the Sources page, where the authorization is started.
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

    var source = sources.findByOrganizationId(organization.id()).orElse(null);
    var versions = briefs.findAllByOrganizationId(organization.id());
    // Cached by OrganizationSecurity, which admits no request without one, so this is a read rather than a query.
    var membership = (Member) req.getAttribute(OrganizationSecurity.MEMBER_ATTRIBUTE);
    render("pages/detail.jte", req, res, new OrganizationDetailView(organization, source, versions, membership));
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

    var brief = briefs.findByOrganizationIdAndVersion(organization.id(), version).orElse(null);
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
    var sourcesByOrganization = sources.findAll()
                                       .stream()
                                       .collect(Collectors.toMap(BriefSource::organizationId, s -> s));
    // Versions only, never findLatest(): this page renders one integer per Organization, and findLatest() carries
    // every Brief's full document with it.
    var latestVersions = briefs.findLatestVersions();

    // The viewer's Organizations, not all of them: membership is what makes one visible here. PENDING rows are
    // deliberately included, because this listing is how an invited user finds the Organization to accept -- but
    // they render as invitations above the listing, with Accept and Decline, rather than as rows in it. An
    // Organization the viewer has not joined yet has no status worth a table row.
    var userId = oidc.user().userId();
    var membershipsByOrganization = members.findAllByUserId(userId)
                                           .stream()
                                           .collect(Collectors.toMap(Member::organizationId, m -> m));

    var invitations = new ArrayList<OrganizationsView.Invitation>();
    var rows = new ArrayList<OrganizationsView.Row>();
    for (var o : organizations.findAllByMember(userId)) {
      var membership = membershipsByOrganization.get(o.id());
      if (membership.state() == MembershipState.PENDING) {
        invitations.add(new OrganizationsView.Invitation(o.id(), o.name(), membership.role()));
        continue;
      }

      // A source that has been connected but not registered has nothing to show yet, so the row reads exactly as
      // one with no source at all: it is the Sources page that tells the two apart.
      var source = sourcesByOrganization.get(o.id());
      var registered = source != null && source.registered();
      rows.add(new OrganizationsView.Row(
          o.id(),
          o.name(),
          membership.role(),
          registered ? source.type() : null,
          registered ? source.source() : null,
          registered ? source.config().branch() : null,
          source == null ? null : source.lastStatus(),
          source == null ? null : source.lastError(),
          latestVersions.get(o.id()),
          source == null ? null : source.lastPolledInstant()));
    }

    render("pages/organizations.jte", req, res, new OrganizationsView(invitations, rows));
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

  /**
   * The Sources page: every kind of source this server offers, and the state of the one the Organization has. Where
   * the OAuth callbacks land; the outcome they queued is the layout's to show.
   */
  public void sources(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var source = sources.findByOrganizationId(organization.id()).orElse(null);
    render("pages/sources.jte", req, res, new OrganizationSourcesView(organization, source, catalog.available()));
  }

  /**
   * Saves the Agent selection. {@code all} wins whenever it is present -- with scripting, the individual boxes are
   * disabled and not submitted while All is checked; without it, they may arrive alongside and are ignored. A
   * rejected selection re-renders the form as submitted, so the reason shows next to what produced it.
   */
  public void updateAgents(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      Main.missing(req, res);
      return;
    }

    var all = req.getParameter("all") != null;
    var selected = new ArrayList<Agent>();
    var submitted = req.getParameters("agents");
    for (var value : submitted == null ? List.<String>of() : submitted) {
      // An unknown value is a stale or hand-edited form, not a selection; dropping it is what rejecting a
      // now-empty selection below is for.
      try {
        selected.add(Agent.valueOf(value));
      } catch (IllegalArgumentException _) {
        // Ignored.
      }
    }

    try {
      organizationService.updateAgents(organization, all ? null : new Agents(selected));
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
    } catch (ValidationException e) {
      render("pages/agents.jte", req, res, new OrganizationAgentsView(organization, all, selected, e.errors()));
    }
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

    var brief = briefs.findByOrganizationIdAndVersion(organization.id(), version).orElse(null);
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
        brief.insertInstant(), brief.organization().agents(), entries);
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

    return organizations.findById(id).orElse(null);
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

  private void renderForm(HTTPRequest req, HTTPResponse res, List<String> errors, String name) throws IOException {
    res.setStatus(200);
    // Not render(): this page's parameters are two separate values rather than one view model, so its map is built
    // here. The viewer still has to be in it, for the same layout.
    templates.html("pages/new.jte", req, res, Map.of("errors", errors, "name", name, "viewer", oidc.user()));
  }
}
