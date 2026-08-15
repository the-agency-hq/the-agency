/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.validation;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.model.InviteRequest;
import dev.theagencyhq.agency.model.Member;
import dev.theagencyhq.agency.model.MembershipState;
import dev.theagencyhq.agency.model.Role;
import dev.theagencyhq.agency.model.User;

/**
 * The membership rules, ported from {@code latte-java/app}. The one that matters is the last-owner rule: every path
 * that would leave an Organization without a single ACTIVE OWNER — demoting the last one, removing them, or the last
 * one leaving — is refused, because an Organization nobody can administer is unrecoverable from the UI.
 */
public final class MembershipValidator {
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private MembershipValidator() {
  }

  /**
   * @param organizationId The Organization.
   * @param targetUserId   The member whose role is changing.
   * @param newRole        The role they are changing to.
   * @param current        The signed-in user making the change.
   * @param database       The database.
   * @throws ValidationException if the change is a self-change or would demote the last ACTIVE OWNER.
   */
  public static void validateChangeRole(UUID organizationId, UUID targetUserId, Role newRole, User current,
                                        DatabaseService database) {
    if (current.userId().equals(targetUserId)) {
      throw new ValidationException(List.of("You cannot change your own role."));
    }

    var target = database.findMember(organizationId, targetUserId).orElse(null);
    if (target == null) {
      return;
    }

    if (target.role() == Role.OWNER && target.state() == MembershipState.ACTIVE && newRole != Role.OWNER
        && database.findActiveOwners(organizationId).size() <= 1) {
      throw new ValidationException(List.of("The last active Owner cannot be demoted."));
    }
  }

  /**
   * @param request The invite form's submission.
   * @throws ValidationException if the email is missing or malformed, or the role is missing.
   */
  public static void validateInvite(InviteRequest request) {
    var errors = new ArrayList<String>();
    var email = request.email() == null ? "" : request.email();
    if (email.isEmpty()) {
      errors.add("An email address is required.");
    } else if (!EMAIL_PATTERN.matcher(email).matches()) {
      errors.add("The email address [" + email + "] is not a valid format.");
    }

    if (request.role() == null) {
      errors.add("A role is required.");
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  /**
   * @param organizationId The Organization.
   * @param current        The signed-in user leaving it.
   * @param database       The database.
   * @throws ValidationException if leaving would leave the Organization without an ACTIVE OWNER.
   */
  public static void validateLeave(UUID organizationId, User current, DatabaseService database) {
    var member = database.findMember(organizationId, current.userId()).orElse(null);
    if (member == null) {
      return;
    }

    if (member.role() == Role.OWNER && member.state() == MembershipState.ACTIVE
        && database.findActiveOwners(organizationId).size() <= 1) {
      throw new ValidationException(List.of("You are the last active Owner, so you cannot leave. Promote another "
          + "member to Owner first."));
    }
  }

  /**
   * @param organizationId The Organization.
   * @param userId         The FusionAuth user UUID the email resolved to.
   * @param email          The email, for the message.
   * @param database       The database.
   * @throws ValidationException if the user already has a membership row — ACTIVE or a pending invitation.
   */
  public static void validateNoDuplicateMembership(UUID organizationId, UUID userId, String email,
                                                   DatabaseService database) {
    if (database.findMember(organizationId, userId).isPresent()) {
      throw new ValidationException(
          List.of("[" + email + "] is already a member or has a pending invitation."));
    }
  }

  /**
   * @param organizationId The Organization.
   * @param targetUserId   The member being removed.
   * @param current        The signed-in user doing the removing.
   * @param database       The database.
   * @throws ValidationException if the removal is a self-removal or would remove the last ACTIVE OWNER.
   */
  public static void validateRemove(UUID organizationId, UUID targetUserId, User current, DatabaseService database) {
    if (current.userId().equals(targetUserId)) {
      throw new ValidationException(List.of("You cannot remove yourself. Leave the Organization instead."));
    }

    Optional<Member> target = database.findMember(organizationId, targetUserId);
    if (target.isEmpty()) {
      return;
    }

    if (target.get().role() == Role.OWNER && target.get().state() == MembershipState.ACTIVE
        && database.findActiveOwners(organizationId).size() <= 1) {
      throw new ValidationException(List.of("The last active Owner cannot be removed."));
    }
  }
}
