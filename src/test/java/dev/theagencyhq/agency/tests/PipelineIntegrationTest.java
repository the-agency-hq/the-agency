/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.model.*;
import dev.theagencyhq.agency.model.api.*;
import dev.theagencyhq.agency.tests.github.FakeGitHubClient;

import static org.testng.Assert.*;

/**
 * Drives the whole pipeline end to end -- connect a GitHub repository, poll it, and hit {@code POST /api/v1/briefing}
 * -- rather than re-testing any one unit. The eight scenarios form a single narrative and run inside one test method
 * (TestNG does not guarantee declaration order) so each one builds on the database and repository state the previous
 * one left behind: 1-3 build, edit, and no-op the one Organization this class registers; 4-6 exercise the Briefing API
 * against that same Organization; 7 registers and deletes a second, throwaway Organization so the deletion scenario
 * cannot disturb the state scenario 8 still needs; 8 resumes the original Organization to prove a build failure does
 * not roll back what is already being served.
 */
@Test(groups = "integration")
public class PipelineIntegrationTest extends BaseTest {
  private final List<UUID> organizationIds = new ArrayList<>();
  private final JSONBodyAsserter json = new JSONBodyAsserter();
  private final StringBodyAsserter string = new StringBodyAsserter();
  private String lastChecksum;
  private int lastVersion;
  private Organization organization;
  private FakeGitHubClient.Repository repository;

  // Deletes every Organization this class registered (immediately tracked in organizationIds as each is created),
  // so the one agency_test database every other test class shares is left exactly as this class found it.
  // alwaysRun = true: a failure partway through the pipeline (e.g. scenario 3 throwing before scenario 8 ever runs)
  // must still trigger this cleanup. Deleting an id scenario 7 already deleted itself is a harmless no-op.
  @AfterClass(alwaysRun = true)
  public void afterClass() {
    if (db != null) {
      organizationIds.forEach(db::deleteOrganization);
    }
  }

  @Test
  public void fullSystem() throws Exception {
    connectAndPollProducesVersionOne();
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

    repository.removeFile("the-agency-hq-settings.json");

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

  private void contentChangeProducesVersionTwo() {
    repository.putFile("rules/rule1.md", "rule one, edited\n");

    assertEquals(runCycle(organization.id()), SourceStatus.OK);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 2);
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

  private void connectAndPollProducesVersionOne() {
    repository = github.add("theagencyhq", "pipeline-briefs")
                       .putFile("skills/skill1/SKILL.md", "skill one\n")
                       .putFile("rules/rule1.md", "rule one\n")
                       .putFile("claude/settings.json", "{}\n")
                       .putFile("codex/config.toml", "x = 1\n");

    organization = organizationService.create("pipeline-" + UUID.randomUUID(), testUser);
    organizationIds.add(organization.id());
    linkGitHub(organization.id());
    connect(organization.id(), "theagencyhq", "pipeline-briefs");

    assertEquals(runCycle(organization.id()), SourceStatus.OK);

    var brief = db.findLatestBrief(organization.id()).orElseThrow();
    assertEquals(brief.version().intValue(), 1);

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

    // The commit is GitHub's, carried onto the Brief as its provenance, and it is what the next cycle compares
    // against to decide whether there is anything to do at all.
    assertEquals(brief.sourceCommit(), db.findSource(organization.id()).orElseThrow().lastBuiltCommit());
  }

  private String currentVersionsBody(UUID organizationId, int version, String checksum) {
    return "{\"currentVersions\":[{\"organizationId\":\"" + organizationId + "\",\"version\":" + version
        + ",\"checksum\":\"" + checksum + "\"}]}";
  }

  // Registers and deletes its own, second Organization rather than the one scenarios 1-6 built up: scenario 8
  // still needs that Organization's repository and version-2 state intact, so the deletion this scenario proves
  // must land on state nothing downstream depends on.
  private void deletingAnOrganizationForcesA200() throws Exception {
    github.add("theagencyhq", "throwaway-briefs").putFile("rules/a.md", "throwaway\n");

    var throwaway = organizationService.create("pipeline-throwaway-" + UUID.randomUUID(), testUser);
    organizationIds.add(throwaway.id());
    linkGitHub(throwaway.id());
    connect(throwaway.id(), "theagencyhq", "throwaway-briefs");

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

  private void repeatedRequestIsNotModified() throws Exception {
    briefing(currentVersionsBody(organization.id(), lastVersion, lastChecksum))
        .assertStatus(304)
        .assertBodyAs(string, StringBodyAsserter::isEmpty);
  }

  private void unrelatedCommitProducesNoNewVersion() {
    repository.putFile("README.md", "unrelated\n");

    assertEquals(runCycle(organization.id()), SourceStatus.UNCHANGED);
    assertEquals(db.findLatestBrief(organization.id()).orElseThrow().version().intValue(), 2);
  }
}
