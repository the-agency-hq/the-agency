/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The Agent selection end to end: the Owner's form, the {@code organizations} row, the version a change publishes,
 * and what the Briefing API then serves. The stored Brief keeps every file throughout — only the wire is reduced.
 */
@Test(groups = "integration")
public class AgentSelectionIntegrationTest extends BaseTest {
  private static final String AGENTS_FILE = ".agents/AGENTS.md";
  private static final String CLAUDE_RULE = ".claude/rules/a.md";
  private static final String CODEX_CONFIG = ".codex/config.toml";
  private static final String STANDARD_SKILL = ".agents/skills/s/SKILL.md";
  public StringBodyAsserter string = new StringBodyAsserter();

  private static List<String> paths(Brief brief) {
    return brief.files().stream().map(BriefFile::path).toList();
  }

  @Test
  public void aContributorIsTurnedAway() throws Exception {
    var organization = insertOrganization("agents-contributor-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);

    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/" + organization.id())
        .assertStatus(200)
        .assertBodyAs(string, b -> b.doesNotContain("/agents\""));
    test.get("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/");
    test.withFormField("agents", "CLAUDE")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/");
    assertNull(db.findOrganization(organization.id()).orElseThrow().agents());
  }

  /**
   * The All box wins whenever it arrives: with scripting, the individual boxes are disabled and never sent; without
   * it, they may come along and are ignored.
   */
  @Test
  public void allWinsOverIndividualBoxes() {
    var organization = insertOrganization("agents-all-" + UUID.randomUUID());
    organizationService.updateAgents(organization, new Agents(List.of(Agent.KIRO)));

    test.withFormField("all", "on")
        .withFormField("agents", "CLAUDE")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/" + organization.id());
    assertNull(db.findOrganization(organization.id()).orElseThrow().agents());
  }

  @Test
  public void anEmptySelectionIsRejectedAndChangesNothing() {
    var organization = insertOrganization("agents-empty-" + UUID.randomUUID());
    organizationService.updateAgents(organization, new Agents(List.of(Agent.CLAUDE)));
    insertBrief(db.findOrganization(organization.id()).orElseThrow(), "sum-1", briefFile(CLAUDE_RULE, "a"));

    // No fields at all: All unchecked, nothing picked. An unknown value is dropped rather than rejected, so it is
    // the same submission.
    test.withFormField("agents", "NOT_AN_AGENT")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Select at least one Agent, or All."));

    assertEquals(db.findOrganization(organization.id()).orElseThrow().agents(), new Agents(List.of(Agent.CLAUDE)));
    assertEquals(db.listBriefs(organization.id()).size(), 1);
  }

  /**
   * The Handler's side. A Handler holding the latest version is told nothing changed; an Owner narrowing the
   * selection publishes a version whose number and checksum the Handler does not hold, and the Brief it is then
   * served carries only the files the selected Agents read — while the stored version still carries all of them.
   */
  @Test
  public void theAPIServesOnlyWhatTheSelectedAgentsRead() throws Exception {
    var organization = insertOrganization("agents-api-" + UUID.randomUUID());
    var v1 = insertBrief(organization, "sum-1", briefFile(CLAUDE_RULE, "a"), briefFile(CODEX_CONFIG, "b"),
        briefFile(STANDARD_SKILL, "c"), briefFile(AGENTS_FILE, "d"));

    briefing(orgRequest(organization.id(), 1, "sum-1")).assertStatus(304);

    assertTrue(organizationService.updateAgents(organization, new Agents(List.of(Agent.CODEX))));
    var v2 = db.findLatestBrief(organization.id()).orElseThrow();
    assertEquals(v2.version(), 2);
    assertNotEquals(v2.checksum(), v1.checksum());
    assertEquals(paths(v2), paths(v1), "The stored version keeps every file");

    briefing(orgRequest(organization.id(), 1, "sum-1"))
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("\"version\":2")
                                    .contains("\"checksum\":\"" + v2.checksum() + "\"")
                                    .contains("\"agents\":{\"enabled\":[\"CODEX\"]}")
                                    .contains(CODEX_CONFIG)
                                    .contains(STANDARD_SKILL)
                                    .doesNotContain(CLAUDE_RULE)
                                    .doesNotContain(AGENTS_FILE));

    // Caught up on the new version: nothing to send, even though the Handler holds fewer files than the version.
    briefing(orgRequest(organization.id(), 2, v2.checksum())).assertStatus(304);
  }

  @Test
  public void theFormRendersTheStoredSelection() {
    var organization = insertOrganization("agents-form-" + UUID.randomUUID());

    test.get("/app/organizations/" + organization.id() + "/agents")
        .assertStatus(200)
        .assertBodyAs(string, b -> {
          b.contains("data-agents-all").contains("name=\"all\"").contains("checked");
          for (var agent : Agent.values()) {
            b.contains("value=\"" + agent.name() + "\"").contains(agent.label());
          }
        });

    organizationService.updateAgents(organization, new Agents(List.of(Agent.CURSOR, Agent.CLAUDE)));
    test.get("/app/organizations/" + organization.id() + "/agents")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("value=\"CLAUDE\" checked")
                                    .contains("value=\"CURSOR\" checked")
                                    .doesNotContain("value=\"KIRO\" checked")
                                    .doesNotContain("value=\"on\" checked"));
  }

  /**
   * The Owner's side: the form stores the selection, publishes the latest Brief again under it, and the pages
   * describe both. Saving the same selection again publishes nothing, and going back to All publishes once more.
   */
  @Test
  public void savingASelectionPublishesANewVersion() {
    var organization = insertOrganization("agents-save-" + UUID.randomUUID());
    var v1 = insertBrief(organization, "sum-1", briefFile(CLAUDE_RULE, "a"), briefFile(CODEX_CONFIG, "b"));

    test.withFormField("agents", "CODEX")
        .withFormField("agents", "CLAUDE")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/" + organization.id())
        .reset(ResetItem.Request);

    assertEquals(db.findOrganization(organization.id()).orElseThrow().agents(),
        new Agents(List.of(Agent.CLAUDE, Agent.CODEX)));
    var versions = db.listBriefs(organization.id());
    assertEquals(versions.size(), 2);
    var v2 = versions.getFirst();
    assertEquals(v2.version(), 2);
    assertEquals(v2.organization().agents(), new Agents(List.of(Agent.CLAUDE, Agent.CODEX)));
    assertEquals(paths(v2), paths(v1));
    assertEquals(v2.sourceCommit(), v1.sourceCommit());
    assertNotEquals(v2.checksum(), v1.checksum());
    // The stored document is what BriefBuilder would produce for these files under this selection, so the next
    // poll computes the same checksum and does not publish a duplicate.
    assertEquals(v2.checksum(), BriefBuilder.checksum(new Brief(null, v2.organization(), null, v2.files(), null, null)));

    test.get("/app/organizations/" + organization.id())
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Claude Code, Codex").contains("/agents\""));
    test.get("/app/organizations/" + organization.id() + "/versions/2")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Claude Code, Codex"));
    test.get("/app/organizations/" + organization.id() + "/versions/1")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("All").doesNotContain("Claude Code, Codex"));

    // Unchanged, in a different order: nothing is published.
    test.withFormField("agents", "CLAUDE")
        .withFormField("agents", "CODEX")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/" + organization.id())
        .reset(ResetItem.Request);
    assertEquals(db.listBriefs(organization.id()).size(), 2);

    test.withFormField("all", "on")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/" + organization.id())
        .reset(ResetItem.Request);
    assertNull(db.findOrganization(organization.id()).orElseThrow().agents());
    var v3 = db.findLatestBrief(organization.id()).orElseThrow();
    assertEquals(v3.version(), 3);
    assertNull(v3.organization().agents());
    assertEquals(v3.checksum(), BriefBuilder.checksum(new Brief(null, v3.organization(), null, v3.files(), null, null)));
  }

  @Test
  public void withoutAVersionOnlyTheRowChanges() {
    var organization = insertOrganization("agents-unbuilt-" + UUID.randomUUID());

    test.withFormField("agents", "GEMINI")
        .post("/app/organizations/" + organization.id() + "/agents")
        .assertRedirect(303, "/app/organizations/" + organization.id());

    assertEquals(db.findOrganization(organization.id()).orElseThrow().agents(), new Agents(List.of(Agent.GEMINI)));
    assertTrue(db.listBriefs(organization.id()).isEmpty());
  }

  @BeforeMethod
  public void signIn() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
  }

  private WebTestAsserter briefing(String currentVersions) throws Exception {
    test.clearRequestState();

    var tokens = apiOIDC.login(TEST_EMAIL, TEST_PASSWORD, TEST_REDIRECT_URI);
    return test.withHeader("Authorization", "Bearer " + tokens.accessToken())
               .withHeader("Content-Type", "application/json")
               .withBody("{\"currentVersions\":[" + currentVersions + "]}")
               .post("/api/v1/briefing");
  }

  private String orgRequest(UUID organizationId, int version, String checksum) {
    return "{\"organizationId\":\"" + organizationId + "\",\"version\":" + version + ",\"checksum\":\"" + checksum + "\"}";
  }
}
