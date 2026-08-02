/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.agency.error.*;
import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.tests.*;

import static org.testng.Assert.*;

@Test
public class OrganizationServiceTest extends BaseTest {
  private final List<Path> workTrees = new ArrayList<>();
  private Path root;

  // BaseTest empties the database before every method, so only the temporary Git work trees are this class's to
  // remove. alwaysRun = true so a partial failure still cleans up whatever was created.
  @AfterMethod(alwaysRun = true)
  public void afterMethod() throws IOException {
    for (var tree : workTrees) {
      deleteDirectory(tree);
    }
    workTrees.clear();
    root = null;
  }

  /**
   * The name is display text, so the validator imposes no character set at all — it only has to be present, fit 255
   * characters, and be unique. Case is preserved rather than flattened, which is the point of calling it display text:
   * an Organization registered as "FusionAuth" is shown as "FusionAuth".
   */
  @Test
  public void createAcceptsAnyCharactersInTheNameAndPreservesCase() {
    var name = "FusionAuth Iñtërnâtiônàl — R&D / \"Ops\" <2026> 🕵" + UUID.randomUUID();

    var organization = organizationService.create(name, root.toString());

    assertEquals(organization.name(), name);
    assertEquals(db.findOrganization(organization.id()).orElseThrow().name(), name);
  }

  /**
   * Uniqueness stays case-insensitive now that the stored name keeps its case, and the check has to agree with the
   * {@code LOWER(name)} unique index rather than merely resemble it -- which is why the lookup lowercases both sides in
   * SQL instead of lowercasing its argument in Java.
   */
  @Test
  public void createRejectsANameDifferingOnlyByCase() throws Exception {
    var name = "Acme-" + UUID.randomUUID();
    organizationService.create(name, root.toString());

    // A second work tree, so the only thing wrong with this registration is the name. Reusing the first one's path
    // would trip the path-uniqueness check too, and the assertion below could not tell the two apart.
    var second = workTree();
    var exception = expectThrows(ValidationException.class,
        () -> organizationService.create(name.toUpperCase(Locale.ROOT), second.toString()));
    assertEquals(exception.errors().size(), 1, exception.errors().toString());
    assertTrue(exception.errors().getFirst().contains("is already registered"), exception.errors().toString());
  }

  @Test
  public void createRejectsANameOverTheLengthLimit() {
    var tooLong = "a".repeat(256);

    var exception = expectThrows(ValidationException.class, () -> organizationService.create(tooLong, root.toString()));
    assertTrue(exception.errors().getFirst().contains("at most 255 characters"), exception.errors().toString());

    // The boundary is inclusive, so one character shorter registers cleanly.
    var atLimit = organizationService.create("b".repeat(255), root.toString());
    assertEquals(atLimit.name().length(), 255);
  }

  @Test
  public void createRejectsAnUnparseableSettingsFile() throws Exception {
    Files.writeString(root.resolve("the-agency-hq-settings.json"), "this is not JSON");

    var exception = expectThrows(ValidationException.class,
        () -> organizationService.create("org-parse-" + UUID.randomUUID(), root.toString()));
    assertTrue(exception.errors().getFirst().contains("Unable to parse"), exception.errors().toString());
    assertTrue(db.findSourceByPath(root.toString()).isEmpty());
  }

  @Test
  public void createRejectsAnUnsupportedSettingsMajorVersion() throws Exception {
    // Registration PARSES the settings marker rather than merely checking that it exists. Without that, this
    // repository registers cleanly and then fails BUILD_FAILED on every poll cycle from then on -- and the only
    // evidence of the operator's mistake sits on a detail page they have no reason to open yet, arbitrarily long
    // after the form submission that caused it.
    Files.writeString(root.resolve("the-agency-hq-settings.json"), "{\"version\":\"2.0.0\"}");

    var exception = expectThrows(ValidationException.class,
        () -> organizationService.create("org-major-" + UUID.randomUUID(), root.toString()));
    assertTrue(exception.errors().getFirst().contains("unsupported major"), exception.errors().toString());
    assertTrue(db.findSourceByPath(root.toString()).isEmpty());
  }

  @Test
  public void createRollsBackTheOrganizationWhenTheSourceInsertRacesAnExistingPath() {
    var winner = organizationService.create("org-a-" + UUID.randomUUID(), root.toString());

    // Simulates a second create() racing this one on the exact same path: a racing caller's own
    // OrganizationValidator pre-check would have seen the path as free (the winner had not committed yet), so it
    // reaches the transactional insert exactly like the winner did. Calling DatabaseService directly here -- rather
    // than trying to land two concurrent create() calls on the exact same instant, which would be a timing-dependent
    // flake -- reproduces that outcome deterministically: the rollback guarantee under test lives entirely in
    // createOrganizationWithSource, so exercising it directly proves the same thing without relying on scheduling.
    var now = Instant.now();
    var loser = new Organization(UUID.randomUUID(), "org-b-" + UUID.randomUUID(), now, now);
    var loserSource = new BriefSource(UUID.randomUUID(), loser.id(), root.toString(), null, null, null, null, null,
        now, now);

    var exception = expectThrows(ValidationException.class,
        () -> db.createOrganizationWithSource(loser, loserSource));
    assertTrue(exception.errors().getFirst().contains(root.toString()), exception.errors().toString());

    // The whole point: insertOrganization for `loser` must not have survived just because it ran first inside the
    // transaction. An orphaned Organization row with no source is exactly what the rollback exists to prevent.
    assertTrue(db.findOrganization(loser.id()).isEmpty());

    // The winner (and its source) are untouched by the loser's failed, rolled-back attempt.
    assertEquals(db.findOrganization(winner.id()).orElseThrow().id(), winner.id());
    assertEquals(db.findSource(winner.id()).orElseThrow().path(), root.toString());
  }

  // Named apart from BaseTest's beforeMethod, which it must not override: an override cannot add a checked
  // exception, and TestNG runs the superclass's reset first either way.
  @BeforeMethod
  public void createSourceRepository() throws Exception {
    root = workTree();
  }

  /**
   * @return A fresh directory that passes every path check: absolute, existing, a Git work tree, and carrying a
   *     supported settings marker. Extra ones are only needed by tests that must register twice without the second
   *     registration failing for reusing the first one's path.
   */
  private Path workTree() throws Exception {
    var tree = Files.createDirectories(Path.of("build/test/organization-" + UUID.randomUUID()).toAbsolutePath());
    Files.writeString(tree.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    initRepository(tree);
    workTrees.add(tree);
    return tree;
  }
}
