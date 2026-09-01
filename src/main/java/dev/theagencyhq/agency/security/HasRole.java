/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.security;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.model.Member;

/**
 * Per-route middleware requiring the signed-in user to be an ACTIVE member of the path-bound Organization with one
 * of the listed roles. Reads the {@link Member} that {@link OrganizationSecurity} cached on the request, so a route
 * gated by both pays one membership read per request — which makes {@link OrganizationSecurity} a hard prerequisite
 * in the chain, and a missing cache an {@link IllegalStateException} rather than a quiet denial.
 *
 * <p>Every genuine denial — wrong role, PENDING state — redirects to the listing with a 303, exactly as the base
 * check does, so a denied response never says why.
 */
public class HasRole implements Middleware {
  private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";
  private final Set<Role> required;

  HasRole(Role... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }

    this.required = Set.of(roles);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    var organizationId = (String) req.getAttribute(ORGANIZATION_ID_ATTRIBUTE);
    if (organizationId == null) {
      // A route without the attribute is a route this middleware cannot judge: a developer wired it onto the wrong
      // path, which is a bug rather than a denial.
      res.setStatus(500);
      return;
    }

    var membership = (Member) req.getAttribute(OrganizationSecurity.MEMBER_ATTRIBUTE);
    if (membership == null) {
      throw new IllegalStateException("HasRole requires OrganizationSecurity to be installed upstream; no cached "
          + "membership for Organization [" + organizationId + "]");
    }

    if (membership.state() != MembershipState.ACTIVE || !required.contains(membership.role())) {
      res.sendRedirect("/app/organizations/", 303);
      return;
    }

    chain.next(req, res);
  }
}
