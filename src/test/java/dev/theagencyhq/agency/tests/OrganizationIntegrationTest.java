/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module dev.theagencyhq.agency;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The Organization registration rules, driven entirely through HTTP: the name form and the repository picker are
 * what accept and reject, so a rejection is asserted as the re-rendered page carrying the reason and a success as
 * the redirect plus what the database now holds. {@code AdminUIIntegrationTest} covers the pages themselves — this
 * class covers the rules behind them, including the one rejection it already asserts elsewhere (a repository with
 * no settings marker, in {@code rejectsARepositoryThatIsNotABriefSource}), which is deliberately not repeated
 * here.
 */
@Test(groups = "integration")
public class OrganizationIntegrationTest extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();

  /**
   * One repository serves one Organization, case-insensitively, because GitHub repository names are. The picker
   * re-renders with the reason and stores nothing; the {@code LOWER()} unique index below the validator remains
   * the backstop that makes a racing duplicate genuinely impossible rather than merely reported.
   */
  @Test
  public void connectIsRejectedWhenAnotherOrganizationHoldsTheRepositoryCaseInsensitively() throws Exception {
    github.add("Acme", "Briefs");
    var first = createOrganization("org-a-" + UUID.randomUUID());
    linkGitHub(first);
    postConnect(first, "Acme/Briefs", "main")
        .assertRedirect(303, "/app/organizations/" + first)
        .reset(ResetItem.Request);

    github.add("acme", "briefs");
    var second = createOrganization("org-b-" + UUID.randomUUID());
    linkGitHub(second);
    postConnect(second, "acme/briefs", "main")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("already registered to another Organization"))
        .reset(ResetItem.Request);

    assertTrue(db.findSource(second).isEmpty());
    assertEquals(db.findSource(first).orElseThrow().repository(), "Briefs");
  }

  @Test
  public void connectRejectsARepositoryThisAccountCannotSee() throws Exception {
    // Never registered with the fake at all, which is what a repository the GitHub App has not been installed on
    // looks like from here: GitHub simply does not answer for it.
    var organizationId = createOrganization("org-invisible-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "acme/private", "main")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("acme/private"))
        .reset(ResetItem.Request);
    assertTrue(db.findSource(organizationId).isEmpty());
  }

  @Test
  public void connectRejectsAnUnknownBranch() throws Exception {
    github.add("acme", "briefs");
    var organizationId = createOrganization("org-branch-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "acme/briefs", "does-not-exist")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("does-not-exist"))
        .reset(ResetItem.Request);
    assertTrue(db.findSource(organizationId).isEmpty());
  }

  @Test
  public void connectRejectsAnUnparseableSettingsFile() throws Exception {
    github.add("acme", "briefs").putFile("the-agency-hq-settings.json", "this is not JSON");
    var organizationId = createOrganization("org-parse-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "acme/briefs", "main")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Unable to parse"))
        .reset(ResetItem.Request);
    assertTrue(db.findSource(organizationId).isEmpty());
  }

  /**
   * Registration parses the settings marker rather than merely checking that it exists. Without that, this
   * repository connects cleanly and then fails {@code BUILD_FAILED} on every poll cycle from then on — and the only
   * evidence of the operator's mistake sits on a detail page they have no reason to open yet, arbitrarily long
   * after the form submission that caused it.
   */
  @Test
  public void connectRejectsAnUnsupportedSettingsMajorVersion() throws Exception {
    github.add("acme", "briefs").putFile("the-agency-hq-settings.json", "{\"version\":\"2.0.0\"}");
    var organizationId = createOrganization("org-major-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "acme/briefs", "main")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("unsupported major"))
        .reset(ResetItem.Request);
    assertTrue(db.findSource(organizationId).isEmpty());
  }

  /**
   * Reconnecting replaces the source rather than adding a second one, and drops the poll history with it. Carrying
   * {@code lastBuiltCommit} across a change of repository would let the next cycle compare the new repository's
   * head against the old one's and, if they happened to agree, skip the build that was the entire point.
   */
  @Test
  public void connectReplacesAnExistingSourceAndClearsItsHistory() throws Exception {
    github.add("acme", "briefs");
    github.add("acme", "other-briefs");
    var organizationId = createOrganization("org-replace-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "acme/briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);
    rebuild(organizationId);
    var built = db.findSource(organizationId).orElseThrow();
    assertEquals(built.lastStatus(), SourceStatus.OK);
    assertNotNull(built.lastBuiltCommit());

    postConnect(organizationId, "acme/other-briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var source = db.findSource(organizationId).orElseThrow();
    assertEquals(source.repository(), "other-briefs");
    assertNull(source.lastBuiltCommit());
    assertNull(source.lastStatus());
    assertEquals(db.listSources().size(), 1);
  }

  @Test
  public void connectStoresTheRepositoryAsGitHubSpellsIt() throws Exception {
    github.add("Acme-Corp", "Brief-Sources").defaultBranch("trunk");
    var organizationId = createOrganization("org-case-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "Acme-Corp/Brief-Sources", "trunk")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var source = db.findSource(organizationId).orElseThrow();
    assertEquals(source.owner(), "Acme-Corp");
    assertEquals(source.repository(), "Brief-Sources");
    assertEquals(source.branch(), "trunk");
    assertEquals(source.fullName(), "Acme-Corp/Brief-Sources");
    assertEquals(source.url(), "https://github.com/Acme-Corp/Brief-Sources");
  }

  /**
   * The name is display text, so the form imposes no character set at all — it only has to be present, fit 255
   * characters, and be unique. Case is preserved rather than flattened, which is the point of calling it display
   * text: an Organization registered as "FusionAuth" is shown as "FusionAuth". Round-tripping this name through a
   * real form post is also what proves the encoding survives the whole way down.
   */
  @Test
  public void createAcceptsAnyCharactersInTheNameAndPreservesCase() {
    var name = "FusionAuth Iñtërnâtiônàl — R&D / \"Ops\" <2026> 🕵" + UUID.randomUUID();

    var organizationId = createOrganization(name);

    assertEquals(db.findOrganization(organizationId).orElseThrow().name(), name);
  }

  /**
   * An Organization exists from the moment it is named, with no source at all. That is an ordinary state rather
   * than a broken one — it is where every Organization sits between step one and step two of registration — and
   * the poller has to have nothing to say about it.
   */
  @Test
  public void createLeavesTheOrganizationWithNoSource() {
    var organizationId = createOrganization("org-unconnected-" + UUID.randomUUID());

    assertTrue(db.findSource(organizationId).isEmpty());
    assertTrue(db.listSources().isEmpty());
  }

  /**
   * Uniqueness stays case-insensitive now that the stored name keeps its case, and the check has to agree with the
   * {@code LOWER(name)} unique index rather than merely resemble it -- which is why the lookup lowercases both
   * sides in SQL instead of lowercasing its argument in Java.
   */
  @Test
  public void createRejectsANameDifferingOnlyByCase() {
    var name = "Acme-" + UUID.randomUUID();
    createOrganization(name);

    test.withFormField("name", name.toUpperCase(Locale.ROOT))
        .post("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("is already registered"))
        .reset(ResetItem.Request);

    assertEquals(db.listOrganizations().size(), 1);
  }

  @Test
  public void createRejectsANameOverTheLengthLimit() {
    test.withFormField("name", "a".repeat(256))
        .post("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("at most 255 characters"))
        .reset(ResetItem.Request);
    assertEquals(db.listOrganizations().size(), 0);

    // The boundary is inclusive, so one character shorter registers cleanly.
    var organizationId = createOrganization("b".repeat(255));
    assertEquals(db.findOrganization(organizationId).orElseThrow().name().length(), 255);
  }

  /**
   * The creator is seated as the Organization's first member — an ACTIVE OWNER, with no inviter and no invitation
   * timestamps — by the same form post, because an Organization without an ACTIVE OWNER cannot be administered at
   * all.
   */
  @Test
  public void createSeatsTheCreatorAsActiveOwner() {
    var organizationId = createOrganization("org-owner-" + UUID.randomUUID());

    var member = db.findMember(organizationId, testUser.userId()).orElseThrow();
    assertEquals(member.role(), Role.OWNER);
    assertEquals(member.state(), MembershipState.ACTIVE);
    assertNull(member.invitedBy());
    assertNull(member.invitedAt());
    assertNotNull(member.joinedAt());
  }

  // Every route this class posts to is behind the gate, so the session is established once here rather than at the
  // top of each method. One authorization-code flow per test, and BaseTest ends it afterwards.
  @BeforeMethod
  public void signIn() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
  }

  /**
   * Posts the repository picker's form the way the browser does: the repository as one {@code owner/name} field,
   * because that is how GitHub names it everywhere the operator has seen it.
   *
   * @param organizationId The Organization being connected.
   * @param fullName       The repository as {@code owner/name}.
   * @param branch         The branch to build from.
   * @return The asserter, for the caller to chain the expected outcome onto — and then {@code reset} the request.
   */
  private WebTestAsserter postConnect(UUID organizationId, String fullName, String branch) {
    return test.withFormField("repository", fullName)
               .withFormField("branch", branch)
               .post("/app/organizations/" + organizationId + "/connect");
  }
}
