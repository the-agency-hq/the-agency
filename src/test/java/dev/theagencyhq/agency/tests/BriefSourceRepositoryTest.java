/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

/**
 * {@code BriefSourceRepository} against the real database: every operation, the whole configuration document
 * round-tripping through one codec, and the case-insensitive identity rule the unique index enforces.
 */
@Test
public class BriefSourceRepositoryTest extends BaseTest {
  private static final OAuthConnection CONNECTION = new OAuthConnection("octocat",
      new OAuthTokens("access-1", TEST_INSTANT.plusSeconds(3600), "refresh-1", TEST_INSTANT.plusSeconds(86_400)));

  @Test
  public void createsReadsUpdatesAndDeletes() {
    var organization = insertOrganization();

    // A GitHub source as the OAuth callback creates it: connected, not yet registered. The whole configuration,
    // credential included, round-trips through the JSONB document as one codec.
    var source = new BriefSource(UUID.randomUUID(), organization.id(), new GitHubConfig(CONNECTION, null, null,
        null), null, null, null, null, TEST_INSTANT, TEST_INSTANT);
    sources.create(source);
    assertEquals(sources.findById(source.id()).orElseThrow(), source);
    assertEquals(sources.findByOrganizationId(organization.id()).orElseThrow(), source);
    assertEquals(sources.findAll(), List.of(source));
    assertTrue(sources.findByOrganizationId(UUID.randomUUID()).isEmpty());

    // The whole row, from the model: configuration, history, and update instant move; the id, the Organization, and
    // the insert instant do not.
    var later = TEST_INSTANT.plusSeconds(60);
    var repository = "briefs-" + UUID.randomUUID();
    var registered = new BriefSource(source.id(), organization.id(), new GitHubConfig(CONNECTION, "Acme", repository,
        "main"), "abc123", later, SourceStatus.OK, null, TEST_INSTANT, later);
    assertTrue(sources.update(registered));
    var stored = sources.findByOrganizationId(organization.id()).orElseThrow();
    assertEquals(stored, registered);
    assertEquals(stored.source(), "Acme/" + repository);
    assertEquals(stored.type(), BriefSourceType.GITHUB);

    // The identity column follows the document: case-insensitively, matching the LOWER() unique index the lookup
    // has to agree with rather than merely resemble -- GitHub treats `Acme/briefs` and `acme/BRIEFS` as one
    // repository, and so must this.
    assertEquals(sources.findBySource(BriefSourceType.GITHUB, "acme/" + repository.toUpperCase(Locale.ROOT))
                        .orElseThrow().id(), source.id());
    assertTrue(sources.findBySource(BriefSourceType.GITHUB, "acme/other").isEmpty());

    // Disconnected: the document without its connection, the repository and history exactly as they were.
    var disconnected = registered.withConfig(new GitHubConfig(null, "Acme", repository, "main"), later);
    assertTrue(sources.update(disconnected));
    assertEquals(sources.findByOrganizationId(organization.id()).orElseThrow(), disconnected);

    // A row that is not there is not written.
    assertFalse(sources.update(new BriefSource(UUID.randomUUID(), organization.id(), new GitHubConfig(null, null,
        null, null), null, null, null, null, later, later)));

    sources.delete(source.id());
    assertTrue(sources.findByOrganizationId(organization.id()).isEmpty());
    sources.delete(source.id());
  }

  /**
   * A GitLab source round-trips through the same column and the same codec under its own discriminator, and its
   * identity is unique per kind: the very same string registered as a GitHub repository by another Organization is
   * a different source, because the index is over {@code (type, LOWER(source))} and not over the name alone.
   */
  @Test
  public void storesAGitLabSourceBesideAGitHubSourceOfTheSameName() {
    var first = insertOrganization();
    var second = insertOrganization();
    var project = "acme/platform/briefs-" + UUID.randomUUID();

    var gitLab = new BriefSource(UUID.randomUUID(), first.id(),
        new GitLabConfig(CONNECTION, "https://gitlab.com", project, "main"), null, null, null, null, TEST_INSTANT,
        TEST_INSTANT);
    sources.create(gitLab);
    var stored = sources.findByOrganizationId(first.id()).orElseThrow();
    assertEquals(stored, gitLab);
    assertEquals(stored.type(), BriefSourceType.GITLAB);
    assertEquals(stored.source(), project);
    assertEquals(stored.config().fullName(), project);
    assertEquals(sources.findBySource(BriefSourceType.GITLAB, project.toUpperCase(Locale.ROOT)).orElseThrow().id(),
        gitLab.id());
    assertTrue(sources.findBySource(BriefSourceType.GITHUB, project).isEmpty());

    var row = database.dsl()
                .resultQuery("SELECT type, source_config->>'type', source_config->>'project', source_config->>'baseURL' "
                    + "FROM brief_sources WHERE id = ?", gitLab.id())
                .fetchOne();
    assertEquals(row.get(0, String.class), "GITLAB");
    assertEquals(row.get(1, String.class), "GITLAB");
    assertEquals(row.get(2, String.class), project);
    assertEquals(row.get(3, String.class), "https://gitlab.com");

    // GitHub cannot spell an owner with a slash in it, but the database does not know that: the same identity under
    // the other kind is simply another source.
    var gitHub = new BriefSource(UUID.randomUUID(), second.id(),
        new GitHubConfig(CONNECTION, null, null, null).withRepository(project, "main"), null, null, null, null,
        TEST_INSTANT, TEST_INSTANT);
    sources.create(gitHub);
    assertEquals(sources.findBySource(BriefSourceType.GITHUB, project).orElseThrow().id(), gitHub.id());
    assertEquals(sources.findAll().size(), 2);
  }

  /**
   * One Organization holds one source, so an upsert matches on the Organization: an absent row is inserted, a
   * present one is replaced whole — keeping the id and insert instant it already had, since the row is the same
   * row.
   */
  @Test
  public void upsertsByOrganization() {
    var organization = insertOrganization();
    var created = new BriefSource(UUID.randomUUID(), organization.id(), new GitHubConfig(CONNECTION, "Acme",
        "briefs-" + UUID.randomUUID(), "main"), "abc123", TEST_INSTANT, SourceStatus.OK, null, TEST_INSTANT,
        TEST_INSTANT);
    sources.upsert(created);
    assertEquals(sources.findByOrganizationId(organization.id()).orElseThrow(), created);

    var later = TEST_INSTANT.plusSeconds(60);
    var replacement = new BriefSource(UUID.randomUUID(), organization.id(), new GitHubConfig(CONNECTION, null, null,
        null), null, null, null, null, later, later);
    sources.upsert(replacement);
    var stored = sources.findByOrganizationId(organization.id()).orElseThrow();
    assertEquals(stored.id(), created.id());
    assertEquals(stored.insertInstant(), TEST_INSTANT);
    assertEquals(stored.updateInstant(), later);
    assertEquals(stored.config(), replacement.config());
    assertNull(stored.source());
    assertNull(stored.lastStatus());
    assertEquals(sources.findAll().size(), 1);
  }

  /**
   * A source identity is unique per kind, case-insensitively, however it is written — create, update, or upsert —
   * and the violation surfaces as the same {@code ValidationException} the validator's up-front check throws.
   */
  @Test
  public void rejectsADuplicateIdentityInAnyCase() {
    var first = insertOrganization();
    var second = insertOrganization();
    var repository = "briefs-" + UUID.randomUUID();
    sources.create(new BriefSource(UUID.randomUUID(), first.id(), new GitHubConfig(CONNECTION, "Acme", repository,
        "main"), null, null, null, null, TEST_INSTANT, TEST_INSTANT));
    var other = new BriefSource(UUID.randomUUID(), second.id(), new GitHubConfig(CONNECTION, null, null, null),
        null, null, null, null, TEST_INSTANT, TEST_INSTANT);
    sources.create(other);

    var taken = new GitHubConfig(CONNECTION, "ACME", repository.toUpperCase(Locale.ROOT), "main");
    var expected = List.of("The source [ACME/" + repository.toUpperCase(Locale.ROOT)
        + "] is already registered to another Organization.");
    assertEquals(expectThrows(ValidationException.class, () -> sources.update(other.withConfig(taken, TEST_INSTANT)))
        .errors(), expected);
    assertEquals(expectThrows(ValidationException.class, () -> sources.upsert(other.withConfig(taken, TEST_INSTANT)))
        .errors(), expected);
    assertEquals(expectThrows(ValidationException.class, () -> sources.create(new BriefSource(UUID.randomUUID(),
        insertOrganization().id(), taken, null, null, null, null, TEST_INSTANT, TEST_INSTANT))).errors(), expected);

    // Nothing was written by any of them.
    assertFalse(sources.findByOrganizationId(second.id()).orElseThrow().registered());
    assertEquals(sources.findAll().size(), 2);
  }
}
