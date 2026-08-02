/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.model.BriefFile;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.view.BriefFileView;
import dev.theagencyhq.agency.model.view.BriefVersionView;
import dev.theagencyhq.agency.model.view.OrganizationDetailView;
import dev.theagencyhq.agency.model.view.OrganizationsView;
import dev.theagencyhq.agency.service.Services;

/**
 * The admin UI: create an Organization, register its source path, trigger a rebuild, and inspect exactly what a
 * Brief version contains. No authentication — this binds to localhost only; OIDC is a later milestone.
 */
public class OrganizationController {
  private final JTETemplates templates;

  public OrganizationController(JTETemplates templates) {
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

  public void create(HTTPRequest req, HTTPResponse res) throws IOException {
    var name = req.getParameter("name");
    var path = req.getParameter("path");
    try {
      var organization = Services.organizationService().create(name, path);
      res.sendRedirect("/app/organizations/" + organization.id(), 303);
    } catch (ValidationException e) {
      renderForm(req, res, e.errors(), name == null ? "" : name, path == null ? "" : path);
    }
  }

  public void detail(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      res.setStatus(404);
      return;
    }

    var source = Services.databaseService().findSource(organization.id()).orElse(null);
    var versions = Services.databaseService().listBriefs(organization.id());
    templates.html("pages/detail.jte", req, res, new OrganizationDetailView(organization, source, versions));
  }

  public void file(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      res.setStatus(404);
      return;
    }

    var version = parseInt((String) req.getAttribute("version"));
    var index = parseInt((String) req.getAttribute("index"));
    if (version == null || index == null) {
      res.setStatus(404);
      return;
    }

    var brief = Services.databaseService().findBrief(organization.id(), version).orElse(null);
    if (brief == null) {
      res.setStatus(404);
      return;
    }

    var files = brief.files();
    if (index < 0 || index >= files.size()) {
      res.setStatus(404);
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

    templates.html("pages/file.jte", req, res, new BriefFileView(organization, version, file, text, bytes.length));
  }

  public void list(HTTPRequest req, HTTPResponse res) throws IOException {
    var database = Services.databaseService();
    var sourcesByOrganization = database.listSources()
                                        .stream()
                                        .collect(Collectors.toMap(BriefSource::organizationId, s -> s));
    // Versions only, never latestBriefs(): this page renders one integer per Organization, and latestBriefs()
    // carries every Brief's full document with it.
    var latestVersions = database.latestBriefVersions();

    var rows = database.listOrganizations().stream().map(o -> {
      var source = sourcesByOrganization.get(o.id());
      return new OrganizationsView.Row(
          o.id(),
          o.name(),
          source == null ? "" : source.path(),
          source == null ? null : source.lastStatus(),
          source == null ? null : source.lastError(),
          source == null ? null : source.lastPullError(),
          latestVersions.get(o.id()),
          source == null ? null : source.lastPolledInstant());
    }).toList();

    templates.html("pages/organizations.jte", req, res, new OrganizationsView(rows));
  }

  public void newForm(HTTPRequest req, HTTPResponse res) throws IOException {
    renderForm(req, res, List.of(), "", "");
  }

  public void rebuild(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      res.setStatus(404);
      return;
    }

    // A nudge, not a build. Running the pull and the build on the request thread would hold an HTTP worker for as
    // long as the slowest `git pull` takes, and it is the only thing that would ever build an Organization off the
    // poller thread -- which is what forced the per-Organization lock this class used to depend on. The cycle is
    // where the result appears, so the detail page reports the source's status whenever the admin next loads it.
    Services.pollerService().nudge();
    res.sendRedirect("/app/organizations/" + organization.id(), 303);
  }

  public void version(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = findOrganization(req);
    if (organization == null) {
      res.setStatus(404);
      return;
    }

    var version = parseInt((String) req.getAttribute("version"));
    if (version == null) {
      res.setStatus(404);
      return;
    }

    var brief = Services.databaseService().findBrief(organization.id(), version).orElse(null);
    if (brief == null) {
      res.setStatus(404);
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
    templates.html("pages/version.jte", req, res, view);
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

    return Services.databaseService().findOrganization(id).orElse(null);
  }

  private void renderForm(HTTPRequest req, HTTPResponse res, List<String> errors, String name, String path)
      throws IOException {
    res.setStatus(200);
    templates.html("pages/new.jte", req, res, Map.of("errors", errors, "name", name, "path", path));
  }
}
