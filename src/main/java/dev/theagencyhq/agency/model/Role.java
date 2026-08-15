/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * A member's role in an Organization, mirroring {@code latte-java/app}. Two roles only: OWNER is the role that
 * manages, CONTRIBUTOR is the role that works.
 */
public enum Role {
  CONTRIBUTOR("Contributor", "View the Organization, its Briefs, and trigger rebuilds."),
  OWNER("Owner", "Manage members and the GitHub connection, plus everything a Contributor can do.");

  private final String description;
  private final String label;

  Role(String label, String description) {
    this.label = label;
    this.description = description;
  }

  public String description() {
    return description;
  }

  public String label() {
    return label;
  }
}
