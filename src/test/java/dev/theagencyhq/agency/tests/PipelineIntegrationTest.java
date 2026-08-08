/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.api.*;

import static org.testng.Assert.*;

/**
 * Drives the whole pipeline end to end -- register a Brief source, poll it, and hit {@code POST /api/v1/briefing} --
 * rather than re-testing any one unit. The eight scenarios form a single narrative and are wired together with
 * {@code dependsOnMethods} (TestNG does not guarantee declaration order) so each one builds on the database and Git
 * state the previous one left behind: 1-3 build, edit, and no-op the one Organization this class registers; 4-6
 * exercise the Briefing API against that same Organization; 7 registers and deletes a second, throwaway Organization so
 * the deletion scenario cannot disturb the state scenario 8 still needs; 8 resumes the original Organization to prove a
 * build failure does not roll back what is already being served.
 */
@Test
public class PipelineIntegrationTest extends BaseTest {
  private final List<UUID> organizationIds = new ArrayList<>();
  private final List<Path> roots = new ArrayList<>();
  private final JSONBodyAsserter json = new JSONBodyAsserter();
  private final StringBodyAsserter string = new StringBodyAsserter();
  private String lastChecksum;
  private int lastVersion;
  private Organization organization;
  private Path root;

  // Deletes every Organization this class registered (immediately tracked in organizationIds as each is created)
  // and every temporary Git repository it created, so the one agency_test database and filesystem every other test
  // class shares are left exactly as this class found them. alwaysRun = true: a failure partway through the
  // pipeline (e.g. scenario 3 throwing before scenario 8 ever runs) must still trigger this cleanup, or the
  // Organization and directory created in scenario 1 outlive this class. Deleting an id scenario 7 already
  // deleted itself is a harmless no-op.
  @AfterClass(alwaysRun = true)
  public void afterClass() throws IOException {
    if (db != null) {
      organizationIds.forEach(db::deleteOrganization);
    }

    for (var directory : roots) {
      deleteDirectory(directory);
    }
  }

  @Test
  public void fullSystem() throws Exception {
    registerCommitAndPollProducesVersionOne();
    contentChangeProducesVersionTwo();
    unrelatedCommitProducesNoNewVersion();
    handlerColdStoreReceivesEveryBrief();
    repeatedRequestIsNotModified();
    corruptChecksumForcesAResend();
    deletingAnOrganizationForcesA200();
    buildFailureLeavesThePreviousVersionServing();
  }

  private void buildFailureLeavesThePreviousVersionServing() throws Exception {
    var before = db.findLatestBrief(organization.id()).orElseThrow();

    Files.delete(root.resolve("the-agency-hq-settings.json"));
    commit(root, "remove the settings marker");

    assertEquals(runCycle(organization.id()), SourceStatus.BUILD_FAILED);

    var after = db.findLatestBrief(organization.id()).orElseThrow();
    assertEquals(after.version(), before.version());
    assertEquals(after.checksum(), before.checksum());

    briefing("{\"currentVersions\":[]}")
        .assertStatus(200)
        // The whole response: the still-serving Brief, unchanged, and nothing else alongside it.
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON,
            briefingResponse(List.of(organization), List.of(before))));
  }

  private void contentChangeProducesVersionTwo() throws Exception {
    Files.writeString(root.resolve("rules/rule1.md"), "rule one, edited\n");
    commit(root, "edit rule1");

    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 2);
  }

  private void corruptChecksumForcesAResend() throws Exception {
    briefing(currentVersionsBody(organization.id(), lastVersion, "not-" + lastChecksum))
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON,
            briefingResponse(List.of(organization), List.of(db.findLatestBrief(organization.id()).orElseThrow()))));
  }

  /**
   * One Briefing API call, with the request state it needs and nothing left over. The eight scenarios below run
   * inside a single test method, so without clearing first the headers of one would ride along on the next — the
   * tester accumulates them until something empties it.
   *
   * @param body The request body.
   * @return The asserter for the response.
   */
  private WebTestAsserter briefing(String body) throws Exception {
    test.clearRequestState();
    var tokens = apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);
    return test.withHeader("Authorization", "Bearer " + tokens.accessToken())
               .withHeader("Content-Type", "application/json")
                       .withBody(body)
                       .post("/api/v1/briefing");
  }

  private String currentVersionsBody(UUID organizationId, int version, String checksum) {
    return "{\"currentVersions\":[{\"organizationId\":\"" + organizationId + "\",\"version\":" + version
        + ",\"checksum\":\"" + checksum + "\"}]}";
  }

  // Registers and deletes its own, second Organization rather than the one scenarios 1-6 built up: scenario 8
  // still needs that Organization's Git history and version-2 state intact, so the deletion this scenario proves
  // must land on state nothing downstream depends on.
  private void deletingAnOrganizationForcesA200() throws Exception {
    var throwawayRoot = Files.createTempDirectory("agency-pipeline-throwaway-");
    roots.add(throwawayRoot);
    Files.writeString(throwawayRoot.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    Files.createDirectories(throwawayRoot.resolve("rules"));
    Files.writeString(throwawayRoot.resolve("rules/a.md"), "throwaway\n");
    initRepository(throwawayRoot);

    var throwaway = organizationService.create("pipeline-throwaway-" + UUID.randomUUID(), throwawayRoot.toString());
    organizationIds.add(throwaway.id());

    assertEquals(runCycle(throwaway.id()), SourceStatus.OK);
    var brief = db.findLatestBrief(throwaway.id()).orElseThrow();

    organizationService.delete(throwaway.id());
    assertTrue(db.findOrganization(throwaway.id()).isEmpty());

    // The Handler still asserts the exact version/checksum it held before the Organization vanished. The set
    // comparison in BriefingService.decide is what turns that into a 200 instead of a 304 -- without it, a deleted
    // Organization's last Handler would poll forever and never learn the Location should be torn down.
    briefing(currentVersionsBody(throwaway.id(), brief.version(), brief.checksum()))
        .assertStatus(200)
        // The deleted Organization is absent from the entitled set, which is what tells the Handler to tear its
        // Location down. Comparing the whole response asserts both halves at once: it is gone from
        // organizationIds, and no Brief of its comes along either.
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON,
            briefingResponse(List.of(organization), List.of(db.findLatestBrief(organization.id()).orElseThrow()))));
  }

  private void handlerColdStoreReceivesEveryBrief() throws Exception {
    var brief = db.findLatestBrief(organization.id()).orElseThrow();
    lastVersion = brief.version();
    lastChecksum = brief.checksum();

    briefing("{\"currentVersions\":[]}")
        .assertStatus(200)
        .assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON,
            briefingResponse(List.of(organization), List.of(brief))));
  }

  private void registerCommitAndPollProducesVersionOne() throws Exception {
    // The system temp directory rather than this project's usual build/test/ (design §14), and deliberately so:
    // this class is the one test that exercises the pipeline the way an operator actually uses it, registering a
    // repository that has no relationship at all to the Agency's own checkout. A fixture under build/test/ is a
    // subdirectory of this repository's Git work tree, so its `git init` creates a repository nested inside another
    // one -- workable, and what the narrower unit tests do, but not the shape being integration-tested here.
    // Cleanup is unconditional via afterClass, so nothing is left in the temp directory either way.
    root = Files.createTempDirectory("agency-pipeline-");
    roots.add(root);
    writeFixtureRoot(root);
    initRepository(root);

    organization = organizationService.create("pipeline-" + UUID.randomUUID(), root.toString());
    organizationIds.add(organization.id());

    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    var brief = db.findLatestBrief(organization.id()).orElseThrow();
    assertEquals(brief.version(), 1);

    // Two shared directories (skills, rules) mapped to both agent types is 2*2 = 4 files, plus the two escape
    // hatches (claude/settings.json, codex/config.toml) which map to exactly one agent type each is 2 more: six
    // in total. This is derived directly from OutputPaths.map's actual mapping rules (verified against
    // BriefBuilderTest), not assumed.
    assertEquals(brief.files().stream().map(BriefFile::path).toList(), List.of(
        ".claude/rules/rule1.md",
        ".claude/settings.json",
        ".claude/skills/skill1/SKILL.md",
        ".codex/config.toml",
        ".codex/rules/rule1.md",
        ".codex/skills/skill1/SKILL.md"));
    brief.files().forEach(f -> assertEquals(f.mode(), "r--------", "Unexpected mode for [" + f.path() + "]"));
  }

  private void repeatedRequestIsNotModified() throws Exception {
    briefing(currentVersionsBody(organization.id(), lastVersion, lastChecksum))
        .assertStatus(304)
        .assertBodyAs(string, StringBodyAsserter::isEmpty);
  }

  /**
   * Runs one real cycle and reports the status it recorded for one Organization. The cycle polls every source
   * registered at that moment, which for this class means the scenarios' shared Organization and, during scenario 7,
   * the throwaway one alongside it — reading the status back off the named Organization's own {@code brief_sources} row
   * is what keeps each scenario's assertion scoped to the Organization it is about.
   */
  private SourceStatus runCycle(UUID organizationId) {
    pollerService.testRun();
    return db.findSource(organizationId).orElseThrow().lastStatus();
  }

  private void unrelatedCommitProducesNoNewVersion() throws Exception {
    Files.writeString(root.resolve("README.md"), "unrelated\n");
    commit(root, "add README");

    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version(), 2);
  }

  private void writeFixtureRoot(Path root) throws IOException {
    Files.writeString(root.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    Files.createDirectories(root.resolve("skills/skill1"));
    Files.writeString(root.resolve("skills/skill1/SKILL.md"), "skill one\n");
    Files.createDirectories(root.resolve("rules"));
    Files.writeString(root.resolve("rules/rule1.md"), "rule one\n");
    Files.createDirectories(root.resolve("claude"));
    Files.writeString(root.resolve("claude/settings.json"), "{}\n");
    Files.createDirectories(root.resolve("codex"));
    Files.writeString(root.resolve("codex/config.toml"), "x = 1\n");
  }
}
