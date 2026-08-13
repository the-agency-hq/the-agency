/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.github;

/**
 * A GitHub call that could not be completed: a transport failure, an unparseable body, or a status code that
 * carries no meaning this API assigns one to.
 *
 * <p>Deliberately unchecked, and deliberately not thrown for the outcomes that <em>are</em> meaningful. An expired
 * token, a repository the caller cannot see, and a file that is not there are all answers rather than faults, so
 * they come back as {@code null} or an empty {@link java.util.Optional} and the caller decides what they mean. This
 * is only ever raised for something no caller can act on.
 */
public class GitHubException extends RuntimeException {
  public GitHubException(String message) {
    super(message);
  }

  public GitHubException(String message, Throwable cause) {
    super(message, cause);
  }
}
