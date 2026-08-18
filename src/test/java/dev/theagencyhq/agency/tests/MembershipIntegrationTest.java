/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module dev.theagencyhq.agency;
import module org.lattejava.fusionauth;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Membership end to end, driven entirely through HTTP with the two kickstart users: {@code testUser} owns what it
 * creates, {@code ordinaryUser} is the one invited, promoted, removed, and turned away. This covers the boundary —
 * {@code OrganizationSecurity} on the prefix and the {@code HasRole(OWNER)} gates per route — and the rules behind
 * the forms: invitations resolve or create real FusionAuth users, and the invite validation and self-service
 * refusals re-render their pages with the reason.
 *
 * <p>One rule is only partially reachable from here. The last-owner protection can fire through {@code leave} —
 * the leaver can be the last ACTIVE OWNER — and that is asserted below. Its demote and remove branches cannot: the
 * actor on those routes must themself be an ACTIVE OWNER, so the target is never the last one. Those branches sit
 * in {@code MembershipValidator} as defense in depth below the routes, and what is asserted here instead is the
 * positive case they permit — an Owner demoting or removing the other Owner succeeds.
 */
@Test(groups = "integration")
public class MembershipIntegrationTest extends BaseTest {
  public StringBodyAsserter string = new StringBodyAsserter();

  /**
   * An ACTIVE Contributor can work — the detail page and rebuild — but every management surface is Owner-only and
   * answers with the silent redirect: the members pages, the picker, and the OAuth start that would swap the
   * Organization's GitHub credential.
   */
  @Test
  public void aContributorCanWorkButCannotManage() throws Exception {
    var organization = insertOrganization("members-contributor-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);

    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/" + organization.id())
        .assertStatus(200)
        // The management actions are not offered to a Contributor, and Leave is.
        .assertBodyAs(string, b -> b.doesNotContain("/members/\"").doesNotContain("/connect\"")
                                    .contains("/members/leave"));

    test.post("/app/organizations/" + organization.id() + "/rebuild")
        .assertRedirect(303, "/app/organizations/" + organization.id());

    for (var path : List.of(
        "/app/organizations/" + organization.id() + "/connect",
        "/app/organizations/" + organization.id() + "/members/",
        "/app/organizations/" + organization.id() + "/members/invite",
        "/app/organizations/" + organization.id() + "/members/" + testUser.userId() + "/role",
        "/app/organizations/" + organization.id() + "/members/" + testUser.userId() + "/remove",
        "/app/oauth/github/start?organizationId=" + organization.id())) {
      test.get(path)
          .assertRedirect(303, "/app/organizations/");
    }
  }

  /**
   * Every Organization-scoped page answers a non-member with the same silent redirect, so a probe cannot tell an
   * Organization that exists from one that does not — and the listing shows them nothing either.
   */
  @Test
  public void aNonMemberIsRedirectedFromEveryOrganizationPageAndSeesAnEmptyListing() throws Exception {
    var organization = insertOrganization("members-foreign-" + UUID.randomUUID());

    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    for (var path : List.of(
        "/app/organizations/" + organization.id(),
        "/app/organizations/" + organization.id() + "/connect",
        "/app/organizations/" + organization.id() + "/versions/1",
        "/app/organizations/" + organization.id() + "/members/",
        "/app/organizations/" + organization.id() + "/members/invite")) {
      test.get(path)
          .assertRedirect(303, "/app/organizations/");
    }
    test.post("/app/organizations/" + organization.id() + "/rebuild")
        .assertRedirect(303, "/app/organizations/");

    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.doesNotContain(organization.name()).contains("No Organizations yet."));
  }

  /**
   * The invite validation, on the page that caused it: a blank email and a malformed one both re-render the form
   * with the reason and the submitted value, and no row is created for either.
   */
  @Test
  public void anInvalidInviteRerendersTheFormWithTheError() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    var organizationId = createOrganization("members-badinvite-" + UUID.randomUUID());

    test.withFormField("email", "  ")
        .withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organizationId + "/members/invite")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("An email address is required."))
        .reset(ResetItem.Request);

    test.withFormField("email", "not-an-email")
        .withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organizationId + "/members/invite")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("not a valid format").contains("value=\"not-an-email\""))
        .reset(ResetItem.Request);

    assertEquals(db.listMembers(organizationId).size(), 1);
  }

  /**
   * The Owner cancels through the same remove route, and a stale Accept afterwards must not grant access — the
   * security middleware has no row to match and turns the POST away before the controller runs.
   */
  @Test
  public void anOwnerCancelsAnInvitationAndAStaleAcceptGrantsNothing() throws Exception {
    var organization = insertOrganization("members-cancel-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.PENDING);

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.post("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/remove")
        .assertRedirect(303, "/app/organizations/" + organization.id() + "/members/");
    assertTrue(db.findMember(organization.id(), ordinaryUser.userId()).isEmpty());

    // The invitee, still holding the page with the Accept button on it.
    ssrOIDC.logout();
    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    test.post("/app/organizations/" + organization.id() + "/members/accept")
        .assertRedirect(303, "/app/organizations/");
    assertTrue(db.findMember(organization.id(), ordinaryUser.userId()).isEmpty());
  }

  /**
   * Both directions of the role form. The demotion is asserted deliberately: the acting Owner is themself a second
   * ACTIVE OWNER, so demoting the other one is always allowed here — the validator's last-owner branch for
   * demotion lives below what these routes can produce (see the class Javadoc).
   */
  @Test
  public void anOwnerChangesRolesInBothDirectionsThroughTheForm() throws Exception {
    var organization = insertOrganization("members-role-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/role")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(ORDINARY_EMAIL).contains("value=\"OWNER\""));

    test.withFormField("role", "OWNER")
        .post("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/role")
        .assertRedirect(303, "/app/organizations/" + organization.id() + "/members/")
        .reset(ResetItem.Request);
    assertEquals(db.findMember(organization.id(), ordinaryUser.userId()).orElseThrow().role(), Role.OWNER);

    test.withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/role")
        .assertRedirect(303, "/app/organizations/" + organization.id() + "/members/")
        .reset(ResetItem.Request);
    assertEquals(db.findMember(organization.id(), ordinaryUser.userId()).orElseThrow().role(), Role.CONTRIBUTOR);
  }

  /**
   * The whole invitation round trip: the Owner posts the invite form, the invitee finds the Organization in their
   * listing, reads the banner on its page, and accepts — becoming ACTIVE with {@code joinedAt} set. Accepting a
   * second time — a stale form resubmission — changes nothing.
   */
  @Test
  public void anOwnerInvitesAndTheInviteeAcceptsThroughThePages() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    var organizationId = createOrganization("members-invite-" + UUID.randomUUID());

    test.get("/app/organizations/" + organizationId + "/members/invite")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("name=\"email\"")
                                    .contains("value=\"CONTRIBUTOR\"")
                                    .contains("value=\"OWNER\""));

    test.withFormField("email", ORDINARY_EMAIL)
        .withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organizationId + "/members/invite")
        .assertRedirect(303, "/app/organizations/" + organizationId + "/members/")
        .reset(ResetItem.Request);

    // The row is keyed to the existing FusionAuth user and records who invited them.
    var invited = db.findMember(organizationId, ordinaryUser.userId()).orElseThrow();
    assertEquals(invited.state(), MembershipState.PENDING);
    assertEquals(invited.role(), Role.CONTRIBUTOR);
    assertEquals(invited.invitedBy(), testUser.userId());
    assertNotNull(invited.invitedAt());
    assertNull(invited.joinedAt());

    // The members page shows the pending row to the Owner, with the cancel control rather than remove.
    test.get("/app/organizations/" + organizationId + "/members/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(ORDINARY_EMAIL).contains("INVITED").contains("Cancel invitation"));

    // The invitee's side. The listing leads with the invitation itself -- name, Accept, and Decline -- so joining
    // does not require finding the Organization's page first.
    ssrOIDC.logout();
    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    var organization = db.findOrganization(organizationId).orElseThrow();
    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(organization.name())
                                    .contains("You've been invited")
                                    .contains("/app/organizations/" + organizationId + "/members/accept")
                                    .contains("/app/organizations/" + organizationId + "/members/decline"));

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("You've been invited"));

    test.post("/app/organizations/" + organizationId + "/members/accept")
        .assertRedirect(303, "/app/organizations/" + organizationId);

    var member = db.findMember(organizationId, ordinaryUser.userId()).orElseThrow();
    assertEquals(member.state(), MembershipState.ACTIVE);
    assertNotNull(member.joinedAt());

    test.get("/app/organizations/" + organizationId)
        .assertStatus(200)
        .assertBodyAs(string, b -> b.doesNotContain("You've been invited"));

    // A second accept is a no-op: joinedAt does not move.
    test.post("/app/organizations/" + organizationId + "/members/accept")
        .assertRedirect(303, "/app/organizations/" + organizationId);
    assertEquals(db.findMember(organizationId, ordinaryUser.userId()).orElseThrow().joinedAt(), member.joinedAt());
  }

  @Test
  public void anOwnerRemovesAMemberThroughTheConfirmationPage() throws Exception {
    var organization = insertOrganization("members-remove-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/remove")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(ORDINARY_EMAIL).contains("Remove"));

    test.post("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/remove")
        .assertRedirect(303, "/app/organizations/" + organization.id() + "/members/");

    assertTrue(db.findMember(organization.id(), ordinaryUser.userId()).isEmpty());

    // A page for a member that does not exist is a 404 -- the target is genuinely missing, not forbidden.
    test.get("/app/organizations/" + organization.id() + "/members/" + ordinaryUser.userId() + "/remove")
        .assertStatus(404);
  }

  /**
   * Refused on the page, with the reason, rather than by a silent redirect — this denial is the user's own doing,
   * and role changes on yourself are Leave-or-promote decisions, not form edits.
   */
  @Test
  public void changingYourOwnRoleIsRefused() throws Exception {
    var organization = insertOrganization("members-selfrole-" + UUID.randomUUID());

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organization.id() + "/members/" + testUser.userId() + "/role")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("your own role"))
        .reset(ResetItem.Request);

    assertEquals(db.findMember(organization.id(), testUser.userId()).orElseThrow().role(), Role.OWNER);
  }

  /**
   * Declining deletes a PENDING row; for an ACTIVE member the same POST is a no-op — an ACTIVE member leaves,
   * they do not decline.
   */
  @Test
  public void decliningDeletesAPendingInvitationAndIsANoOpForActiveMembers() throws Exception {
    var organization = insertOrganization("members-decline-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.PENDING);

    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    test.post("/app/organizations/" + organization.id() + "/members/decline")
        .assertRedirect(303, "/app/organizations/");
    assertTrue(db.findMember(organization.id(), ordinaryUser.userId()).isEmpty());

    ssrOIDC.logout();
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.post("/app/organizations/" + organization.id() + "/members/decline")
        .assertRedirect(303, "/app/organizations/");

    var owner = db.findMember(organization.id(), testUser.userId()).orElseThrow();
    assertEquals(owner.role(), Role.OWNER);
    assertEquals(owner.state(), MembershipState.ACTIVE);
  }

  /**
   * The email is folded to one canonical form before FusionAuth is asked, so a different spelling of a known
   * address resolves to the one existing user rather than minting a duplicate.
   */
  @Test
  public void inviteEmailsAreCaseInsensitive() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    var organizationId = createOrganization("members-case-" + UUID.randomUUID());

    test.withFormField("email", ORDINARY_EMAIL.toUpperCase(Locale.ROOT))
        .withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organizationId + "/members/invite")
        .assertRedirect(303, "/app/organizations/" + organizationId + "/members/")
        .reset(ResetItem.Request);

    assertTrue(db.findMember(organizationId, ordinaryUser.userId()).isPresent());
  }

  /**
   * The unknown-email path: the invitee does not exist in FusionAuth, so the invite registers a brand-new user for
   * the Agency Application — whose set-password email is the invitation — and inserts the PENDING row against the
   * id it minted.
   */
  @Test
  public void invitingAnUnknownEmailRegistersAFusionAuthUserAndAPendingRow() throws Exception {
    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    var organizationId = createOrganization("members-newuser-" + UUID.randomUUID());
    var email = "invitee-" + UUID.randomUUID() + "@theagencyhq.dev";

    test.withFormField("email", email)
        .withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organizationId + "/members/invite")
        .assertRedirect(303, "/app/organizations/" + organizationId + "/members/")
        .reset(ResetItem.Request);

    var created = fusionAuth.retrieveUser(null, null, null, null, email, null);
    assertNotNull(created, "The invite did not register a FusionAuth user for [" + email + "]");
    try {
      var member = db.findMember(organizationId, created.user().id()).orElseThrow();
      assertEquals(member.state(), MembershipState.PENDING);
      assertEquals(member.role(), Role.CONTRIBUTOR);
      assertEquals(member.invitedBy(), testUser.userId());
      assertNotNull(member.invitedAt());
      assertNull(member.joinedAt());
    } finally {
      // The suite's database reset does not reach FusionAuth, so the minted user is removed here or it
      // accumulates across runs.
      fusionAuth.deleteUserWithId(created.user().id(), true,
          UserDeleteSingleRequest.builder().hardDelete(true).build());
    }
  }

  @Test
  public void invitingSomeoneWhoAlreadyHasARowIsRefused() throws Exception {
    var organization = insertOrganization("members-dup-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.PENDING);

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.withFormField("email", ORDINARY_EMAIL)
        .withFormField("role", "CONTRIBUTOR")
        .post("/app/organizations/" + organization.id() + "/members/invite")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("already a member or has a pending invitation"))
        .reset(ResetItem.Request);

    assertEquals(db.listMembers(organization.id()).size(), 2);
  }

  /**
   * The one place the last-owner rule is reachable over HTTP. Refused on the page, with the reason, rather than by
   * a silent redirect, because this denial is the user's own doing — and with a second ACTIVE OWNER seated, the
   * same POST deletes the row.
   */
  @Test
  public void leavingIsRefusedForTheLastOwnerAndWorksOtherwise() throws Exception {
    var organization = insertOrganization("members-leave-" + UUID.randomUUID());

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/" + organization.id() + "/members/leave")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("Leave Organization"));

    test.post("/app/organizations/" + organization.id() + "/members/leave")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("last active Owner"));
    assertTrue(db.findMember(organization.id(), testUser.userId()).isPresent());

    insertMember(organization, ordinaryUser, Role.OWNER, MembershipState.ACTIVE);
    test.post("/app/organizations/" + organization.id() + "/members/leave")
        .assertRedirect(303, "/app/organizations/");
    assertTrue(db.findMember(organization.id(), testUser.userId()).isEmpty());
  }

  /**
   * The mirror of {@link #changingYourOwnRoleIsRefused}: removing yourself is Leave's job, and the page says so
   * instead of doing it.
   */
  @Test
  public void removingYourselfIsRefused() throws Exception {
    var organization = insertOrganization("members-selfremove-" + UUID.randomUUID());

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.post("/app/organizations/" + organization.id() + "/members/" + testUser.userId() + "/remove")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("cannot remove yourself"));

    assertTrue(db.findMember(organization.id(), testUser.userId()).isPresent());
  }

  /**
   * The listing splits the viewer's memberships by state: a PENDING invitation renders as a banner above the table
   * with Accept and Decline, and only ACTIVE memberships render as rows — an Organization the viewer has not
   * joined yet has no status worth one.
   */
  @Test
  public void theListingLeadsWithPendingInvitationsAndKeepsThemOutOfTheTable() throws Exception {
    var joined = insertOrganization("members-listing-joined-" + UUID.randomUUID());
    insertMember(joined, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);
    var invited = insertOrganization("members-listing-invited-" + UUID.randomUUID());
    insertMember(invited, ordinaryUser, Role.OWNER, MembershipState.PENDING);

    ssrOIDC.login(ORDINARY_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b
            // The invitation banner, carrying the role accepting would grant and both controls.
            .contains("You've been invited")
            .contains(invited.name())
            .contains("Accept to join as Owner.")
            .contains("/app/organizations/" + invited.id() + "/members/accept")
            .contains("/app/organizations/" + invited.id() + "/members/decline")
            // The joined Organization is a table row, not a banner: no Accept for it.
            .contains(joined.name())
            .doesNotContain("/app/organizations/" + joined.id() + "/members/accept")
            // And the invited one is not a row: an unconnected row's repository cell would carry this link.
            .doesNotContain("/app/organizations/" + invited.id() + "/connect"));
  }

  /**
   * The members page itself, as the Owner sees it: both members enriched with their FusionAuth identity — the
   * email, from one search over all their ids — and the viewer's own row without self-service controls.
   */
  @Test
  public void theMembersPageListsEveryMemberWithTheirIdentity() throws Exception {
    var organization = insertOrganization("members-page-" + UUID.randomUUID());
    insertMember(organization, ordinaryUser, Role.CONTRIBUTOR, MembershipState.ACTIVE);

    ssrOIDC.login(TEST_EMAIL, TEST_PASSWORD);
    test.get("/app/organizations/" + organization.id() + "/members/")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains(TEST_EMAIL)
                                    .contains(ORDINARY_EMAIL)
                                    .contains("/members/invite")
                                    // The one Remove link is the other member's; the viewer cannot remove
                                    // themselves.
                                    .contains("/members/" + ordinaryUser.userId() + "/remove")
                                    .doesNotContain("/members/" + testUser.userId() + "/remove"));
  }
}
