/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.Main;

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
   * The repository the Organization already builds from is not "another Organization's". Picking it again — to
   * change the branch, or to change nothing at all — goes through like any other connect, replacing the source and
   * its history, rather than re-rendering the picker with a collision against the Organization itself.
   */
  @Test
  public void connectAcceptsTheRepositoryTheOrganizationAlreadyHolds() throws Exception {
    github.add("acme", "briefs").addBranch("trunk");
    var organizationId = createOrganization("org-same-" + UUID.randomUUID());
    linkGitHub(organizationId);
    postConnect(organizationId, "acme/briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);
    rebuild(organizationId);
    assertNotNull(sources.findByOrganizationId(organizationId).orElseThrow().lastBuiltCommit());

    postConnect(organizationId, "acme/briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var same = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(same.source(), "acme/briefs");
    assertEquals(gitHub(organizationId).branch(), "main");
    assertNull(same.lastBuiltCommit());

    postConnect(organizationId, "acme/briefs", "trunk")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    assertEquals(gitHub(organizationId).branch(), "trunk");
    assertEquals(sources.findAll().size(), 1);
  }

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

    assertFalse(sources.findByOrganizationId(second).orElseThrow().registered());
    assertEquals(gitHub(first).repository(), "Briefs");
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
    assertFalse(sources.findByOrganizationId(organizationId).orElseThrow().registered());
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
    assertFalse(sources.findByOrganizationId(organizationId).orElseThrow().registered());
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
    assertFalse(sources.findByOrganizationId(organizationId).orElseThrow().registered());
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
    assertFalse(sources.findByOrganizationId(organizationId).orElseThrow().registered());
  }

  /**
   * Re-picking replaces the repository on the same source row rather than adding a second one, and drops the poll
   * history with it. Carrying {@code lastBuiltCommit} across a change of repository would let the next cycle
   * compare the new repository's head against the old one's and, if they happened to agree, skip the build that
   * was the entire point. What it must not drop is the credential: the merge writes the repository members and
   * nothing else, so the token that verified the new repository is the token that goes on polling it.
   */
  @Test
  public void connectReplacesTheRepositoryAndClearsItsHistoryButKeepsTheCredential() throws Exception {
    github.add("acme", "briefs");
    github.add("acme", "other-briefs");
    var organizationId = createOrganization("org-replace-" + UUID.randomUUID());
    var accessToken = linkGitHub(organizationId);

    postConnect(organizationId, "acme/briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);
    rebuild(organizationId);
    var built = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(built.lastStatus(), SourceStatus.OK);
    assertNotNull(built.lastBuiltCommit());

    postConnect(organizationId, "acme/other-briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    assertEquals(source.id(), built.id());
    assertEquals(source.source(), "acme/other-briefs");
    assertEquals(gitHub(organizationId).repository(), "other-briefs");
    assertNull(source.lastBuiltCommit());
    assertNull(source.lastStatus());
    assertEquals(sources.findAll().size(), 1);
    assertEquals(connection(organizationId).tokens().accessToken(), accessToken);

    // The row's document holds exactly the merged shape: the discriminator, the connection, and the repository.
    var document = database.dsl()
                     .resultQuery("SELECT source_config::text FROM brief_sources WHERE organization_id = ?", organizationId)
                     .fetchOne(0, String.class);
    assertTrue(document.contains("\"type\": \"GITHUB\""), document);
    assertTrue(document.contains("\"repository\": \"other-briefs\""), document);
    assertTrue(document.contains("\"accessToken\": \"" + accessToken + "\""), document);
    assertFalse(document.contains("briefs\"") && document.contains("\"repository\": \"briefs\""), document);
  }

  @Test
  public void connectStoresTheRepositoryAsGitHubSpellsIt() throws Exception {
    github.add("Acme-Corp", "Brief-Sources").defaultBranch("trunk");
    var organizationId = createOrganization("org-case-" + UUID.randomUUID());
    linkGitHub(organizationId);

    postConnect(organizationId, "Acme-Corp/Brief-Sources", "trunk")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);

    var source = sources.findByOrganizationId(organizationId).orElseThrow();
    var config = gitHub(organizationId);
    assertEquals(config.owner(), "Acme-Corp");
    assertEquals(config.repository(), "Brief-Sources");
    assertEquals(config.branch(), "trunk");
    assertEquals(config.fullName(), "Acme-Corp/Brief-Sources");
    assertEquals(config.url(), "https://github.com/Acme-Corp/Brief-Sources");
    assertEquals(source.source(), "Acme-Corp/Brief-Sources");
    assertEquals(sources.findBySource(BriefSourceType.GITHUB, "acme-corp/brief-sources").orElseThrow().id(), source.id());
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

    assertEquals(organizations.findById(organizationId).orElseThrow().name(), name);
  }

  /**
   * An Organization exists from the moment it is named, with no source at all. That is an ordinary state rather
   * than a broken one — it is where every Organization sits between step one and step two of registration — and
   * the poller has to have nothing to say about it.
   */
  @Test
  public void createLeavesTheOrganizationWithNoSource() {
    var organizationId = createOrganization("org-unconnected-" + UUID.randomUUID());

    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
    assertTrue(sources.findAll().isEmpty());
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

    assertEquals(organizations.findAll().size(), 1);
  }

  @Test
  public void createRejectsANameOverTheLengthLimit() {
    test.withFormField("name", "a".repeat(256))
        .post("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("at most 255 characters"))
        .reset(ResetItem.Request);
    assertEquals(organizations.findAll().size(), 0);

    // The boundary is inclusive, so one character shorter registers cleanly.
    var organizationId = createOrganization("b".repeat(255));
    assertEquals(organizations.findById(organizationId).orElseThrow().name().length(), 255);
  }

  /**
   * The creator is seated as the Organization's first member — an ACTIVE OWNER, with no inviter and no invitation
   * timestamps — by the same form post, because an Organization without an ACTIVE OWNER cannot be administered at
   * all.
   */
  @Test
  public void createSeatsTheCreatorAsActiveOwner() {
    var organizationId = createOrganization("org-owner-" + UUID.randomUUID());

    var member = members.findByOrganizationIdAndUserId(organizationId, testUser.userId()).orElseThrow();
    assertEquals(member.role(), Role.OWNER);
    assertEquals(member.state(), MembershipState.ACTIVE);
    assertNull(member.invitedBy());
    assertNull(member.invitedAt());
    assertNotNull(member.joinedAt());
  }

  /**
   * The confirmation is the name: trimmed, because a name pasted from the page can carry whitespace nobody typed,
   * but not case-folded and not a prefix. A refusal re-renders the page with the reason and what was typed, and
   * deletes nothing.
   */
  @Test
  public void deleteIsRefusedUntilTheNameMatchesExactly() throws Exception {
    var name = "org-delete-mismatch-" + UUID.randomUUID();
    var organizationId = createOrganization(name);
    var path = "/app/organizations/" + organizationId + "/delete";

    test.post(path)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("does not match"))
        .reset(ResetItem.Request);
    for (var typed : List.of(name.toUpperCase(Locale.ROOT), name.substring(0, name.length() - 1), name + "x")) {
      test.withFormField("name", typed)
          .post(path)
          .assertStatus(200)
          .assertBodyAs(string, b -> b.contains("does not match").contains("value=\"" + typed + "\""))
          .reset(ResetItem.Request);
    }
    assertTrue(organizations.findById(organizationId).isPresent());

    test.withFormField("name", "  " + name + " ")
        .post(path)
        .assertRedirect(303, "/app/organizations/")
        .reset(ResetItem.Request);
    assertTrue(organizations.findById(organizationId).isEmpty());
  }

  /**
   * Deleting takes the whole Organization with it — the source and its credential, every Brief version, every
   * membership — by the schema's cascades, and the listing the operator lands on says so. The page it is confirmed
   * from says what it is about to take.
   */
  @Test
  public void deleteRemovesTheOrganizationAndEverythingUnderIt() throws Exception {
    github.add("acme", "briefs");
    var name = "org-delete-" + UUID.randomUUID();
    var organizationId = createOrganization(name);
    linkGitHub(organizationId);
    postConnect(organizationId, "acme/briefs", "main")
        .assertRedirect(303, "/app/organizations/" + organizationId)
        .reset(ResetItem.Request);
    rebuild(organizationId);
    insertMember(organizations.findById(organizationId).orElseThrow(), ordinaryUser, Role.CONTRIBUTOR,
        MembershipState.ACTIVE);
    assertEquals(briefs.findAllByOrganizationId(organizationId).size(), 1);

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("/app/organizations/" + organizationId + "/delete"));
    test.get("/app/organizations/" + organizationId + "/delete")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Delete Organization")
                                    .contains("acme/briefs")
                                    .contains("1 Brief version,")
                                    .contains("2 memberships,"));

    test.withFormField("name", name)
        .post("/app/organizations/" + organizationId + "/delete")
        .assertRedirect(303, "/app/organizations/")
        .reset(ResetItem.Request);

    assertTrue(organizations.findById(organizationId).isEmpty());
    assertTrue(sources.findByOrganizationId(organizationId).isEmpty());
    assertTrue(briefs.findAllByOrganizationId(organizationId).isEmpty());
    assertTrue(members.findAllByOrganizationId(organizationId).isEmpty());

    // The notice, read from the same file the handler queues it from, for the path that queued it.
    var messages = new Messages(Main.BASE_DIR, "/app/organizations/" + organizationId + "/delete");
    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(messages.get("deleted", name)));
  }

  // Every route this class posts to is behind the gate, so the session is established once here rather than at the
  // top of each method. One authorization-code flow per test, and BaseTest ends it afterwards.
  @BeforeMethod
  public void signIn() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
  }

  /**
   * @param organizationId The Organization.
   * @return Its GitHub source's configuration, straight off the row.
   */
  private static GitHubConfig gitHub(UUID organizationId) {
    return (GitHubConfig) sources.findByOrganizationId(organizationId).orElseThrow().config();
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
               .post("/app/organizations/" + organizationId + "/sources/github");
  }
}
