/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module dev.theagencyhq.agency;
import module org.lattejava.http;

/**
 * {@code POST /api/v1/briefing} — the endpoint every Handler polls.
 */
public class BriefingController {
  private static final String BEARER = "Bearer ";
  private static final System.Logger logger = System.getLogger(BriefingController.class.getName());
  private final BriefingService briefingService;
  private final Set<String> tokens;

  public BriefingController(BriefingService briefingService, Set<String> tokens) {
    this.briefingService = briefingService;
    this.tokens = Set.copyOf(tokens);
  }

  public void briefing(HTTPRequest req, HTTPResponse res, BriefingRequest body) throws Exception {
    if (!authenticated(req)) {
      res.setStatus(401);
      return;
    }

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
      case BriefingOutcome.Updated updated -> write(res, updated);
    }
  }

  /**
   * Compares against every configured token without short-circuiting, so neither the comparison nor the loop leaks
   * timing information about which token was close.
   */
  private boolean authenticated(HTTPRequest req) {
    var header = req.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      return false;
    }

    var presented = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
    boolean matched = false;
    for (var token : tokens) {
      matched |= MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.UTF_8));
    }

    return matched;
  }

  /**
   * Serializes the envelope through the generated codec. The Briefs are real {@code Brief} objects by the time they
   * reach here — {@code DatabaseService} parses each stored document on the way out — so there is no JSON text to
   * splice and nothing here writes JSON by hand.
   */
  private void write(HTTPResponse res, BriefingOutcome.Updated updated) throws IOException {
    var bytes = BriefingResponseJSON.toJSONBytes(new BriefingResponse(updated.organizationIds(), updated.briefs()));
    res.setStatus(200);
    res.setContentType("application/json");
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);

    logger.log(System.Logger.Level.DEBUG, "Briefing response with [" + updated.briefs().size() + "] Briefs");
  }
}
