/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.Main;
import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.model.InviteRequest;
import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.Role;
import dev.theagencyhq.agency.model.User;
import dev.theagencyhq.agency.model.view.MemberInviteView;
import dev.theagencyhq.agency.model.view.MemberLeaveView;
import dev.theagencyhq.agency.model.view.MemberRemoveView;
import dev.theagencyhq.agency.model.view.MemberRoleView;
import dev.theagencyhq.agency.model.view.MembersView;
import dev.theagencyhq.agency.security.OrganizationSecurity;
import dev.theagencyhq.agency.service.MembershipService;
import dev.theagencyhq.agency.service.Services;

/**
 * The membership pages, ported from {@code latte-java/app}: list an Organization's members, invite by email, change
 * a role, remove a member or cancel an invitation, accept or decline one's own invitation, and leave.
 *
 * <p>Authorization lives in {@code Main}'s route table, not here: {@code OrganizationSecurity} on the prefix
 * guarantees every request that arrives carries a membership row, and the owner-only routes add a
 * {@code HasRole(OWNER)}. By the time a handler runs, the Organization and the caller's own membership are cached
 * on the request, so this class reads them rather than re-resolving.
 */
public class MembershipController {
  public static final String USER_ID = "userId";
  private final MembershipService membershipService;
  private final OIDC<User> oidc;
  private final JTETemplates templates;

  public MembershipController(OIDC<User> oidc, JTETemplates templates) {
    this.membershipService = Services.membershipService();
    this.oidc = oidc;
    this.templates = templates;
  }

  public void accept(HTTPRequest req, HTTPResponse res) {
    var organization = organization(req);
    membershipService.acceptInvitation(organization.id(), oidc.user().userId());
    res.sendRedirect("/app/organizations/" + organization.id(), 303);
  }

  public void changeRole(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    var targetUserId = targetUserId(req);
    if (targetUserId == null) {
      Main.missing(req, res);
      return;
    }

    var newRole = role(req.getParameter("role"), null);
    try {
      if (newRole == null) {
        throw new ValidationException(List.of("A role is required."));
      }

      membershipService.changeRole(organization.id(), targetUserId, newRole, oidc.user());
      res.sendRedirect("/app/organizations/" + organization.id() + "/members/", 303);
    } catch (ValidationException e) {
      renderRoleForm(req, res, organization, targetUserId, newRole, e.errors());
    }
  }

  public void changeRoleForm(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    var targetUserId = targetUserId(req);
    if (targetUserId == null) {
      Main.missing(req, res);
      return;
    }

    renderRoleForm(req, res, organization, targetUserId, null, List.of());
  }

  public void decline(HTTPRequest req, HTTPResponse res) {
    var organization = organization(req);
    membershipService.declineInvitation(organization.id(), oidc.user().userId());
    res.sendRedirect("/app/organizations/", 303);
  }

  public void invite(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    var request = new InviteRequest(organization.id(), req.getParameter("email"),
        role(req.getParameter("role"), Role.CONTRIBUTOR));
    try {
      membershipService.invite(request, oidc.user());
      res.sendRedirect("/app/organizations/" + organization.id() + "/members/", 303);
    } catch (ValidationException e) {
      render("pages/invite.jte", req, res,
          new MemberInviteView(organization, request.email() == null ? "" : request.email(), request.role(),
              e.errors()));
    }
  }

  public void inviteForm(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    render("pages/invite.jte", req, res, new MemberInviteView(organization, "", Role.CONTRIBUTOR, List.of()));
  }

  public void leave(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    try {
      membershipService.leave(organization.id(), oidc.user());
      res.sendRedirect("/app/organizations/", 303);
    } catch (ValidationException e) {
      render("pages/leave.jte", req, res, new MemberLeaveView(organization, membership(req), e.errors()));
    }
  }

  public void leaveForm(HTTPRequest req, HTTPResponse res) throws IOException {
    render("pages/leave.jte", req, res, new MemberLeaveView(organization(req), membership(req), List.of()));
  }

  public void list(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    var members = membershipService.listMembers(organization.id());
    render("pages/members.jte", req, res, new MembersView(organization, members, oidc.user().userId()));
  }

  public void remove(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    var targetUserId = targetUserId(req);
    if (targetUserId == null) {
      Main.missing(req, res);
      return;
    }

    try {
      membershipService.remove(organization.id(), targetUserId, oidc.user());
      res.sendRedirect("/app/organizations/" + organization.id() + "/members/", 303);
    } catch (ValidationException e) {
      renderRemoveForm(req, res, organization, targetUserId, e.errors());
    }
  }

  public void removeForm(HTTPRequest req, HTTPResponse res) throws IOException {
    var organization = organization(req);
    var targetUserId = targetUserId(req);
    if (targetUserId == null) {
      Main.missing(req, res);
      return;
    }

    renderRemoveForm(req, res, organization, targetUserId, List.of());
  }

  /**
   * The submitted role, tolerant of what a hand-built request can carry: {@code fallback} for a missing parameter
   * — the invite form's default — and {@code null} for a value naming no {@link Role}, which the validation then
   * rejects rather than this method throwing a 500.
   */
  private static Role role(String value, Role fallback) {
    if (value == null) {
      return fallback;
    }

    try {
      return Role.valueOf(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * The caller's own membership, cached on the request by {@code OrganizationSecurity}. Never {@code null}: no
   * request reaches this controller without one.
   */
  private static Member membership(HTTPRequest req) {
    return (Member) req.getAttribute(OrganizationSecurity.MEMBER_ATTRIBUTE);
  }

  /**
   * The Organization, cached on the request by {@code OrganizationSecurity}. Never {@code null} for the same
   * reason as {@link #membership}.
   */
  private static Organization organization(HTTPRequest req) {
    return (Organization) req.getAttribute(OrganizationSecurity.ORGANIZATION_ATTRIBUTE);
  }

  private static UUID targetUserId(HTTPRequest req) {
    try {
      return UUID.fromString((String) req.getAttribute(USER_ID));
    } catch (IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  /**
   * Renders a page with its own view model plus the signed-in user, exactly as {@code OrganizationController}
   * does, because the shared layout needs the viewer on every page to draw the chrome.
   */
  private void render(String template, HTTPRequest req, HTTPResponse res, Object model) throws IOException {
    templates.html(template, req, res, Map.of("model", model, "viewer", oidc.user()));
  }

  private void renderRemoveForm(HTTPRequest req, HTTPResponse res, Organization organization, UUID targetUserId,
                                List<String> errors) throws IOException {
    var member = membershipService.findEnrichedMember(organization.id(), targetUserId).orElse(null);
    if (member == null) {
      Main.missing(req, res);
      return;
    }

    render("pages/remove.jte", req, res, new MemberRemoveView(organization, member, errors));
  }

  private void renderRoleForm(HTTPRequest req, HTTPResponse res, Organization organization, UUID targetUserId,
                              Role selectedRole, List<String> errors) throws IOException {
    var member = membershipService.findEnrichedMember(organization.id(), targetUserId).orElse(null);
    if (member == null) {
      Main.missing(req, res);
      return;
    }

    render("pages/role.jte", req, res,
        new MemberRoleView(organization, member, selectedRole != null ? selectedRole : member.role(), errors));
  }
}
