/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module dev.theagencyhq.agency;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.model.api.internal.*;

/**
 * {@code GET /api/v1/organization} — the Organizations the caller has access to, as an
 * {@link OrganizationsResponse} envelope of {@link Organization} documents.
 *
 * <p>Authentication is not this class's job. {@code Main} installs Latte Web's API-mode OIDC middleware on the
 * {@code /api} prefix, so by the time a request reaches this handler its FusionAuth access token has been verified
 * and bound to the request, and {@link OIDC#user()} always resolves. Access is the caller's ACTIVE memberships —
 * the entitlement the 2026-07-30 design (§10.4) promised — computed the same way {@code BriefingService} computes
 * it. A PENDING invitation entitles a Handler to nothing, because nobody has accepted it yet.
 *
 * <p>The envelope carries the Organization records themselves, serialized through the generated codec.
 * {@code gitHubConnection} never serializes ({@code @JSONField(ignore = true)} on the record), so the credential
 * columns cannot leak through this endpoint however the model grows.
 */
public class OrganizationAPIController {
  private static final System.Logger logger = System.getLogger(OrganizationAPIController.class.getName());
  private final DatabaseService database;
  private final OIDC<User> oidc;

  public OrganizationAPIController(OIDC<User> oidc, DatabaseService database) {
    this.database = database;
    this.oidc = oidc;
  }

  public void list(HTTPRequest req, HTTPResponse res) throws IOException {
    // Resolved first, as every controller resolves it first: this is the caller the request is on behalf of, and
    // the memberships the list is narrowed by are theirs.
    var user = oidc.user();

    var organizations = database.listOrganizationsForUser(user.userId(), MembershipState.ACTIVE);
    var bytes = OrganizationsResponseJSON.toJSONBytes(new OrganizationsResponse(organizations));
    res.setStatus(200);
    res.setContentType("application/json");
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);

    // The user id, not the email or username, for the same reasons BriefingController logs it: it is stable, it is
    // the least revealing claim, and it is what entitlements will key on. The access token is never logged.
    logger.log(System.Logger.Level.DEBUG, "Organizations response for user [{0}] with [{1}] Organizations",
        user.userId(), organizations.size());
  }
}
