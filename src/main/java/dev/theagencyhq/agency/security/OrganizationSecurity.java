/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.security;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * The Organization security middleware, ported from {@code latte-java/app}'s {@code GroupSecurity}. Installed once
 * on the {@code /app/organizations} prefix so every Organization-scoped route under it is gated — there is no
 * per-route opt-in to forget. Two phases:
 * <ol>
 *   <li>If the matched route has no {@code organizationId} path attribute ({@code GET /app/organizations/},
 *       {@code /new}, the create POST), the request is not Organization-scoped and passes through unchanged.</li>
 *   <li>Otherwise the Organization must exist and the signed-in user must have a membership row in it. Any failure
 *       — an unparseable id, a missing Organization, or a missing membership — redirects to the listing with a 303
 *       rather than a 404, so the response never says whether the Organization exists.</li>
 * </ol>
 *
 * <p>"Has a membership row" deliberately includes PENDING invitees, so they can open the Organization's page to
 * find the Accept and Decline buttons and so those POSTs reach the controller. The stricter role gates
 * ({@link #hasRole(Role...)}) reject non-ACTIVE rows and wrong roles on top of this base check.
 *
 * <p>{@code Web.install} matches literal path segments only, which is why this is installed at the literal
 * {@code /app/organizations} prefix. The {@code organizationId} attribute is bound by route matching before prefix
 * middleware runs, so the lookup here is reliable.
 */
public class OrganizationSecurity implements Middleware {
  /**
   * Request attribute under which the signed-in user's {@link Member} row for the path-bound Organization is
   * cached after a successful pass. May be PENDING or ACTIVE; downstream readers own any stricter state check.
   */
  public static final String MEMBER_ATTRIBUTE = "organizationSecurity.member";
  /**
   * Request attribute under which the resolved {@link Organization} is cached after a successful pass, so
   * downstream middleware ({@link HasRole}) and handlers never pay a second lookup for a row this middleware
   * already read.
   */
  public static final String ORGANIZATION_ATTRIBUTE = "organizationSecurity.organization";
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private final DatabaseService database;
  private final OIDC<User> oidc;

  public OrganizationSecurity(OIDC<User> oidc) {
    this.database = Services.databaseService();
    this.oidc = oidc;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    var raw = (String) req.getAttribute(ORGANIZATION_ID_ATTRIBUTE);
    if (raw == null) {
      chain.next(req, res);
      return;
    }

    UUID organizationId;
    try {
      organizationId = UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    var organization = database.findOrganization(organizationId).orElse(null);
    if (organization == null) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    var member = database.findMember(organizationId, oidc.user().userId()).orElse(null);
    if (member == null) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    req.setAttribute(ORGANIZATION_ATTRIBUTE, organization);
    req.setAttribute(MEMBER_ATTRIBUTE, member);
    chain.next(req, res);
  }

  /**
   * @param roles One or more roles. The returned middleware lets a request through when the signed-in user's
   *              membership in the path-bound Organization is ACTIVE and holds one of them. Attached per route on
   *              the owner-only endpoints; the base membership check is this {@code OrganizationSecurity} being
   *              installed at the prefix, and {@link HasRole} reads the {@link Member} cached here rather than
   *              re-querying.
   * @return A {@link HasRole} middleware.
   */
  public HasRole hasRole(Role... roles) {
    return new HasRole(roles);
  }
}
