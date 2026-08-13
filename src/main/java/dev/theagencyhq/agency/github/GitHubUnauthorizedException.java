/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.github;

/**
 * GitHub rejected the token outright — HTTP 401. The authorization behind it is gone: revoked by the operator,
 * revoked by an organization owner, or expired past the point a refresh can rescue.
 *
 * <p>Separate from a plain {@link GitHubException} because the two need opposite responses. An ordinary GitHub
 * failure clears itself on the next cycle and needs nobody; this one never clears on its own, and the only fix is a
 * human granting the authorization again. Collapsing them would leave a source retrying forever against a
 * credential that will never work, with a status that says "try again later" to someone who has to act.
 *
 * <p>Not raised for 403 or 404. Those mean the token is fine and this particular repository is not visible to it,
 * which callers report as {@code null} — a different problem with a different fix.
 */
public class GitHubUnauthorizedException extends GitHubException {
  public GitHubUnauthorizedException(String message) {
    super(message);
  }
}
