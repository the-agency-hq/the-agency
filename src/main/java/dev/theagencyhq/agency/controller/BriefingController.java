/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module dev.theagencyhq.agency;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * {@code POST /api/v1/briefing} — the endpoint every Handler polls.
 *
 * <p>Authentication is not this class's job. {@code Main} installs Latte Web's API-mode OIDC middleware on the
 * {@code /api} prefix, so by the time a request reaches this handler its FusionAuth access token has been verified
 * against the provider's JWKS and bound to the request — a missing, malformed, expired, or wrong-audience token was
 * already answered with a {@code 401} and never got here. {@link OIDC#user()} therefore always resolves, and the
 * {@link User} it returns is the developer whose Handler is polling.
 */
public class BriefingController {
  private static final System.Logger logger = System.getLogger(BriefingController.class.getName());
  private final BriefingService briefingService;
  private final OIDC<User> oidc;

  public BriefingController(OIDC<User> oidc, BriefingService briefingService) {
    this.briefingService = briefingService;
    this.oidc = oidc;
  }

  public void briefing(HTTPRequest req, HTTPResponse res, BriefingRequest body) throws Exception {
    // Resolved first, before any work, for the same reason every controller in latte-java/app resolves it first:
    // this is the caller the whole request is on behalf of. Nothing here varies by user yet -- `entitled` is still
    // every Organization (design §10.2, decision 3) -- so it only reaches the response log below, and it is the
    // value the membership lookup will take when entitlements arrive.
    var user = oidc.user();

    // Handle an empty request as though it was a blank assertion
    if (body == null) {
      body = new BriefingRequest(List.of());
    }

    // Two contradictory assertions for one Organization cannot both be honored and picking a winner would silently
    // serve one of them, so the request is rejected instead.
    var ids = body.currentVersions().stream().map(CurrentVersion::organizationId).toList();
    if (new HashSet<>(ids).size() != ids.size()) {
      res.setStatus(400);
      return;
    }

    var outcome = briefingService.decide(body);
    switch (outcome) {
      case BriefingOutcome.NotModified _ -> res.setStatus(304);
      case BriefingOutcome.Updated updated -> write(res, updated, user);
    }
  }

  /**
   * Serializes the envelope through the generated codec. The Briefs are real {@code Brief} objects by the time they
   * reach here — {@code DatabaseService} parses each stored document on the way out — so there is no JSON text to
   * splice and nothing here writes JSON by hand.
   */
  private void write(HTTPResponse res, BriefingOutcome.Updated updated, User user) throws IOException {
    var bytes = BriefingResponseJSON.toJSONBytes(new BriefingResponse(updated.organizationIds(), updated.briefs()));
    res.setStatus(200);
    res.setContentType("application/json");
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);

    // The user id, not the email or username: it is the member the entitled set will eventually be derived from
    // (design §10.4), it is stable across a profile change, and it is the least revealing of the three. The access
    // token it all came from is never logged, at any level.
    logger.log(System.Logger.Level.DEBUG, "Briefing response for user [" + user.userId() + "] with ["
        + updated.briefs().size() + "] Briefs");
  }
}
