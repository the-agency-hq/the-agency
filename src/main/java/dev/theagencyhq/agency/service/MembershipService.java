/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.fusionauth;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.model.InviteRequest;
import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.MembershipState;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.Role;
import dev.theagencyhq.agency.model.User;
import dev.theagencyhq.agency.service.validation.MembershipValidator;
import org.lattejava.web.Configuration;

/**
 * Organization memberships, ported from {@code latte-java/app}'s group memberships. The database holds only the
 * membership rows — user id, role, state — and FusionAuth remains the only user store, so inviting someone is a
 * FusionAuth conversation: an email that resolves to an existing user gets the invitation email, and one that
 * resolves to nobody gets a FusionAuth registration whose set-password email doubles as the invitation. Either way
 * a PENDING row is inserted, and accepting is what turns it ACTIVE.
 */
public class MembershipService {
  /**
   * The Agency Application in FusionAuth — the same id {@code kickstart.json} provisions as
   * {@code agencyApplicationId} and {@code fusionauth.clientId} carries in configuration. A brand-new invitee is
   * registered for it so that, once their password is set, the admin UI's login flow accepts them.
   */
  private static final UUID APPLICATION_ID = UUID.fromString("7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742");
  /**
   * The invitation email template, provisioned by {@code kickstart.json} as {@code inviteEmailTemplateId}. Sent to
   * invitees who already have a FusionAuth account; new invitees get the tenant's set-password email instead, which
   * is written as an invitation.
   */
  private static final UUID INVITE_TEMPLATE_ID = UUID.fromString("2a157fd1-0918-4eca-aa87-8332b9a4bebb");
  private static final System.Logger logger = System.getLogger(MembershipService.class.getName());
  private final DatabaseService database;
  private final FusionAuthClient fusionAuth;

  public MembershipService(Configuration config, DatabaseService database) {
    this.database = database;
    this.fusionAuth = new FusionAuthClient(config.get("fusionauth.apiKey"), config.get("fusionauth.baseURL"));
  }

  /**
   * Turns the caller's PENDING row ACTIVE. A no-op for a row that is missing or already ACTIVE, so a stale form
   * resubmission changes nothing.
   *
   * @param organizationId The Organization.
   * @param userId         The invitee accepting.
   */
  public void acceptInvitation(UUID organizationId, UUID userId) {
    var member = database.findMember(organizationId, userId).orElse(null);
    if (member == null || member.state() != MembershipState.PENDING) {
      return;
    }

    database.updateMemberState(organizationId, userId, MembershipState.ACTIVE, Instant.now());
  }

  /**
   * @param organizationId The Organization.
   * @param targetUserId   The member whose role is changing.
   * @param newRole        The new role.
   * @param current        The signed-in user making the change.
   * @throws ValidationException if the change is a self-change or would demote the last ACTIVE OWNER.
   */
  public void changeRole(UUID organizationId, UUID targetUserId, Role newRole, User current) {
    MembershipValidator.validateChangeRole(organizationId, targetUserId, newRole, current, database);
    database.updateMemberRole(organizationId, targetUserId, newRole);
  }

  /**
   * Deletes the caller's PENDING row. A no-op for a row that is missing or already ACTIVE — an ACTIVE member
   * declines nothing; they leave.
   *
   * @param organizationId The Organization.
   * @param userId         The invitee declining.
   */
  public void declineInvitation(UUID organizationId, UUID userId) {
    var member = database.findMember(organizationId, userId).orElse(null);
    if (member == null || member.state() != MembershipState.PENDING) {
      return;
    }

    database.deleteMember(organizationId, userId);
  }

  /**
   * Like {@link #findMember}, but with the returned {@link Member#user()} enriched from FusionAuth so callers that
   * need email or username — the role and remove confirmation pages — get one row read plus one FusionAuth lookup
   * rather than re-fetching every member of the Organization through {@link #listMembers}.
   *
   * @param organizationId The Organization.
   * @param userId         The member's FusionAuth user UUID.
   * @return The enriched member, or empty if no row exists.
   */
  public Optional<Member> findEnrichedMember(UUID organizationId, UUID userId) {
    var memberOpt = database.findMember(organizationId, userId);
    if (memberOpt.isEmpty()) {
      return memberOpt;
    }

    var member = memberOpt.get();
    var response = fusionAuth.retrieveUserWithId(userId);
    User user;
    if (response == null) {
      logger.log(System.Logger.Level.WARNING, "FusionAuth has no user for member [{0}] in Organization [{1}]",
          member.userId(), member.organizationId());
      user = member.user();
    } else {
      user = UserService.toUser(response.user());
    }

    return Optional.of(new Member(member.organizationId(), user, member.role(), member.state(), member.invitedBy(),
        member.invitedAt(), member.joinedAt()));
  }

  public Optional<Member> findMember(UUID organizationId, UUID userId) {
    return database.findMember(organizationId, userId);
  }

  /**
   * Invites someone by email. An email FusionAuth already knows gets the invitation email; an unknown one gets a
   * brand-new FusionAuth user registered for the Agency Application, whose set-password email is the invitation.
   * Both paths end with a PENDING membership row that {@link #acceptInvitation} turns ACTIVE.
   *
   * @param request The invite form's submission.
   * @param inviter The signed-in user sending the invitation.
   * @return The PENDING member.
   * @throws ValidationException if the email or role is invalid, or the user already has a membership row.
   */
  public Member invite(InviteRequest request, User inviter) {
    MembershipValidator.validateInvite(request);

    var email = request.email();
    var lookup = fusionAuth.retrieveUser(null, null, null, null, email, null);
    UUID userId;
    User invitee;
    if (lookup == null) {
      userId = UUID.randomUUID();
      invitee = new User(userId, email, null);
      var registrationRequest = RegistrationRequest.builder()
                                                   .user(org.lattejava.fusionauth.domain.User.builder()
                                                                                             .id(userId)
                                                                                             .email(email)
                                                                                             .build())
                                                   .registration(UserRegistration.builder()
                                                                                 .applicationId(APPLICATION_ID)
                                                                                 .id(userId)
                                                                                 .build())
                                                   .sendSetPasswordIdentityType(SendSetPasswordIdentityType.email)
                                                   .build();
      fusionAuth.registerWithId(userId, registrationRequest);
    } else {
      userId = lookup.user().id();
      invitee = UserService.toUser(lookup.user());

      // Only possible for an existing user: a brand-new user cannot already hold a row.
      MembershipValidator.validateNoDuplicateMembership(request.organizationId(), userId, email, database);

      var organizationName = database.findOrganization(request.organizationId())
                                     .map(Organization::name)
                                     .orElse("");
      var sendRequest = SendRequest.builder()
                                   .userIds(List.of(userId))
                                   .requestData(Map.of("organizationName", organizationName))
                                   .build();
      fusionAuth.sendEmailWithId(INVITE_TEMPLATE_ID, sendRequest);
    }

    var member = new Member(request.organizationId(), invitee, request.role(), MembershipState.PENDING,
        inviter.userId(), Instant.now(), null);
    database.insertMember(member);
    return member;
  }

  /**
   * @param organizationId The Organization the caller is leaving.
   * @param current        The signed-in user.
   * @throws ValidationException if leaving would leave the Organization without an ACTIVE OWNER.
   */
  public void leave(UUID organizationId, User current) {
    MembershipValidator.validateLeave(organizationId, current, database);
    database.deleteMember(organizationId, current.userId());
  }

  /**
   * Every member of an Organization, each enriched with email and username from one FusionAuth search over all
   * their ids. A member FusionAuth has no user for renders with what the row holds — the UUID — rather than
   * failing the whole page.
   *
   * @param organizationId The Organization.
   * @return Its members.
   */
  public List<Member> listMembers(UUID organizationId) {
    var members = database.listMembers(organizationId);
    if (members.isEmpty()) {
      return members;
    }

    var ids = members.stream().map(Member::userId).toList();
    var response = fusionAuth.searchUsersByIdsWithId(ids, null, null, null, null, null);
    var byId = new HashMap<UUID, org.lattejava.fusionauth.domain.User>();
    if (response != null) {
      for (var user : response.users()) {
        byId.put(user.id(), user);
      }
    }

    var enriched = new ArrayList<Member>(members.size());
    for (var member : members) {
      var fusionAuthUser = byId.get(member.userId());
      User user;
      if (fusionAuthUser == null) {
        logger.log(System.Logger.Level.WARNING, "FusionAuth has no user for member [{0}] in Organization [{1}]",
            member.userId(), member.organizationId());
        user = member.user();
      } else {
        user = UserService.toUser(fusionAuthUser);
      }

      enriched.add(new Member(member.organizationId(), user, member.role(), member.state(), member.invitedBy(),
          member.invitedAt(), member.joinedAt()));
    }

    return enriched;
  }

  /**
   * @param organizationId The Organization.
   * @param targetUserId   The member being removed — or, for a PENDING row, the invitation being cancelled.
   * @param current        The signed-in user doing the removing.
   * @throws ValidationException if the removal is a self-removal or would remove the last ACTIVE OWNER.
   */
  public void remove(UUID organizationId, UUID targetUserId, User current) {
    MembershipValidator.validateRemove(organizationId, targetUserId, current, database);
    database.deleteMember(organizationId, targetUserId);
  }
}
