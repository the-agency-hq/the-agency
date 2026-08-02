/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

/**
 * Thrown when a Brief cannot be built from a source tree. A build is all-or-nothing — a Brief is never inserted
 * partially — so this always aborts the whole build for that Organization.
 */
public class BriefBuildException extends RuntimeException {
  public BriefBuildException(String message) {
    super(message);
  }

  public BriefBuildException(String message, Throwable cause) {
    super(message, cause);
  }
}
