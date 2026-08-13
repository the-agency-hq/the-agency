/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.error.*;
import dev.theagencyhq.agency.tests.*;

import static org.testng.Assert.*;

@Test
public class OrganizationServiceTest extends BaseTest {
  /**
   * The name is display text, so the validator imposes no character set at all — it only has to be present, fit 255
   * characters, and be unique. Case is preserved rather than flattened, which is the point of calling it display text:
   * an Organization registered as "FusionAuth" is shown as "FusionAuth".
   */
  @Test
  public void createAcceptsAnyCharactersInTheNameAndPreservesCase() {
    var name = "FusionAuth Iñtërnâtiônàl — R&D / \"Ops\" <2026> 🕵" + UUID.randomUUID();

    var organization = organizationService.create(name);

    assertEquals(organization.name(), name);
    assertEquals(db.findOrganization(organization.id()).orElseThrow().name(), name);
  }

  /**
   * An Organization exists from the moment it is named, with no source at all. That is an ordinary state rather
   * than a broken one — it is where every Organization sits between step one and step two of registration — and
   * the poller has to have nothing to say about it.
   */
  @Test
  public void createLeavesTheOrganizationWithNoSource() {
    var organization = organizationService.create("org-unconnected-" + UUID.randomUUID());

    assertTrue(db.findSource(organization.id()).isEmpty());
    assertTrue(db.listSources().isEmpty());
  }

  /**
   * Uniqueness stays case-insensitive now that the stored name keeps its case, and the check has to agree with the
   * {@code LOWER(name)} unique index rather than merely resemble it -- which is why the lookup lowercases both sides in
   * SQL instead of lowercasing its argument in Java.
   */
  @Test
  public void createRejectsANameDifferingOnlyByCase() {
    var name = "Acme-" + UUID.randomUUID();
    organizationService.create(name);

    var exception = expectThrows(ValidationException.class,
        () -> organizationService.create(name.toUpperCase(Locale.ROOT)));
    assertEquals(exception.errors().size(), 1, exception.errors().toString());
    assertTrue(exception.errors().getFirst().contains("is already registered"), exception.errors().toString());
  }

  @Test
  public void createRejectsANameOverTheLengthLimit() {
    var tooLong = "a".repeat(256);

    var exception = expectThrows(ValidationException.class, () -> organizationService.create(tooLong));
    assertTrue(exception.errors().getFirst().contains("at most 255 characters"), exception.errors().toString());

    // The boundary is inclusive, so one character shorter registers cleanly.
    assertEquals(organizationService.create("b".repeat(255)).name().length(), 255);
  }

  /**
   * The unique index is what genuinely prevents a duplicate; the validator's up-front check merely reports it
   * politely. Racing the two is not reproducible on demand, so this asserts the guarantee at the layer that holds
   * it: a second insert of the same repository, past the check, still fails — and fails as a
   * {@link ValidationException} rather than a raw jOOQ exception, so a losing form submission reads the same as one
   * that never raced at all.
   */
  @Test
  public void connectIsRejectedWhenAnotherOrganizationHoldsTheRepositoryCaseInsensitively() {
    github.add("Acme", "Briefs");
    var first = organizationService.create("org-a-" + UUID.randomUUID());
    linkGitHub(first.id());
    connect(first.id(), "Acme", "Briefs");

    github.add("acme", "briefs");
    var second = organizationService.create("org-b-" + UUID.randomUUID());
    linkGitHub(second.id());
    var exception = expectThrows(ValidationException.class, () -> connect(second.id(), "acme", "briefs"));
    assertTrue(exception.errors().getFirst().contains("already registered"), exception.errors().toString());

    assertTrue(db.findSource(second.id()).isEmpty());
    assertEquals(db.findSource(first.id()).orElseThrow().repository(), "Briefs");
  }

  @Test
  public void connectRejectsAMissingSettingsFile() {
    github.add("acme", "not-a-brief-source").removeFile("the-agency-hq-settings.json");
    var organization = organizationService.create("org-marker-" + UUID.randomUUID());
    linkGitHub(organization.id());

    var exception = expectThrows(ValidationException.class,
        () -> connect(organization.id(), "acme", "not-a-brief-source"));
    assertTrue(exception.errors().getFirst().contains("is not a Brief source repository"),
        exception.errors().toString());
    assertTrue(db.findSource(organization.id()).isEmpty());
  }

  @Test
  public void connectRejectsAnUnknownBranch() {
    github.add("acme", "briefs");
    var organization = organizationService.create("org-branch-" + UUID.randomUUID());
    linkGitHub(organization.id());

    var exception = expectThrows(ValidationException.class,
        () -> connect(organization.id(), "acme", "briefs", "does-not-exist"));
    assertTrue(exception.errors().getFirst().contains("does-not-exist"), exception.errors().toString());
    assertTrue(db.findSource(organization.id()).isEmpty());
  }

  @Test
  public void connectRejectsAnUnparseableSettingsFile() {
    github.add("acme", "briefs").putFile("the-agency-hq-settings.json", "this is not JSON");
    var organization = organizationService.create("org-parse-" + UUID.randomUUID());
    linkGitHub(organization.id());

    var exception = expectThrows(ValidationException.class, () -> connect(organization.id(), "acme", "briefs"));
    assertTrue(exception.errors().getFirst().contains("Unable to parse"), exception.errors().toString());
    assertTrue(db.findSource(organization.id()).isEmpty());
  }

  /**
   * Registration parses the settings marker rather than merely checking that it exists. Without that, this
   * repository connects cleanly and then fails {@code BUILD_FAILED} on every poll cycle from then on — and the only
   * evidence of the operator's mistake sits on a detail page they have no reason to open yet, arbitrarily long
   * after the form submission that caused it.
   */
  @Test
  public void connectRejectsAnUnsupportedSettingsMajorVersion() {
    github.add("acme", "briefs").putFile("the-agency-hq-settings.json", "{\"version\":\"2.0.0\"}");
    var organization = organizationService.create("org-major-" + UUID.randomUUID());
    linkGitHub(organization.id());

    var exception = expectThrows(ValidationException.class, () -> connect(organization.id(), "acme", "briefs"));
    assertTrue(exception.errors().getFirst().contains("unsupported major"), exception.errors().toString());
    assertTrue(db.findSource(organization.id()).isEmpty());
  }

  @Test
  public void connectRejectsARepositoryThisAccountCannotSee() {
    // Never registered with the fake at all, which is what a repository the GitHub App has not been installed on
    // looks like from here: GitHub simply does not answer for it.
    var organization = organizationService.create("org-invisible-" + UUID.randomUUID());
    linkGitHub(organization.id());

    var exception = expectThrows(ValidationException.class, () -> connect(organization.id(), "acme", "private"));
    assertTrue(exception.errors().getFirst().contains("acme/private"), exception.errors().toString());
    assertTrue(db.findSource(organization.id()).isEmpty());
  }

  /**
   * Reconnecting replaces the source rather than adding a second one, and drops the poll history with it. Carrying
   * {@code lastBuiltCommit} across a change of repository would let the next cycle compare the new repository's
   * head against the old one's and, if they happened to agree, skip the build that was the entire point.
   */
  @Test
  public void connectReplacesAnExistingSourceAndClearsItsHistory() {
    github.add("acme", "briefs");
    github.add("acme", "other-briefs");
    var organization = organizationService.create("org-replace-" + UUID.randomUUID());
    linkGitHub(organization.id());

    connect(organization.id(), "acme", "briefs");
    assertEquals(runCycle(organization.id()), dev.theagencyhq.agency.model.SourceStatus.OK);
    assertNotNull(db.findSource(organization.id()).orElseThrow().lastBuiltCommit());

    connect(organization.id(), "acme", "other-briefs");

    var source = db.findSource(organization.id()).orElseThrow();
    assertEquals(source.repository(), "other-briefs");
    assertNull(source.lastBuiltCommit());
    assertNull(source.lastStatus());
    assertEquals(db.listSources().size(), 1);
  }

  @Test
  public void connectStoresTheRepositoryAsGitHubSpellsIt() {
    github.add("Acme-Corp", "Brief-Sources").defaultBranch("trunk");
    var organization = organizationService.create("org-case-" + UUID.randomUUID());
    linkGitHub(organization.id());

    connect(organization.id(), "Acme-Corp", "Brief-Sources", "trunk");

    var source = db.findSource(organization.id()).orElseThrow();
    assertEquals(source.owner(), "Acme-Corp");
    assertEquals(source.repository(), "Brief-Sources");
    assertEquals(source.branch(), "trunk");
    assertEquals(source.fullName(), "Acme-Corp/Brief-Sources");
    assertEquals(source.url(), "https://github.com/Acme-Corp/Brief-Sources");
  }
}
