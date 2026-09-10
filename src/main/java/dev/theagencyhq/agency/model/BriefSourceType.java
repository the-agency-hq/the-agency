/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * The kinds of Brief source: the {@code brief_sources.type} column, and the discriminator of the
 * {@link BriefSourceConfig} document beside it. The two must agree, and the database checks that they do. Adding a
 * kind is a constant here, a {@link BriefSourceConfig} subtype, a migration widening the column's {@code CHECK},
 * a {@code RepositoryClient} for the host, and its entry in {@code SourceCatalog}.
 */
public enum BriefSourceType {
  GITHUB("GitHub", "github"),
  GITLAB("GitLab", "gitlab");

  private final String label;
  private final String slug;

  BriefSourceType(String label, String slug) {
    this.label = label;
    this.slug = slug;
  }

  /**
   * @param slug A path segment, as a route or a query parameter carried it.
   * @return The kind it names, or {@code null} for anything else — including {@code null}.
   */
  public static BriefSourceType fromSlug(String slug) {
    for (var type : values()) {
      if (type.slug.equals(slug)) {
        return type;
      }
    }

    return null;
  }

  /**
   * @return The name the admin UI shows for the kind.
   */
  public String label() {
    return label;
  }

  /**
   * @return The kind's segment in the admin UI's routes: {@code /app/oauth/{slug}/...} and
   *     {@code /app/organizations/{id}/sources/{slug}}.
   */
  public String slug() {
    return slug;
  }
}
