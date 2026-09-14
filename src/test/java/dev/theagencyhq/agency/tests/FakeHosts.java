/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module dev.theagencyhq.agency;
import module io.avaje.inject;

import dev.theagencyhq.agency.tests.source.FakeRepositoryClient;

/**
 * The fake hosts as beans. Under the {@link Wiring#TEST_PROFILE test profile} these take the place of the real
 * clients {@code Wiring} builds, and {@code Main} needs to know nothing about the suite: Avaje finds this module on
 * the module path, and the profile decides which factory's clients the scope gets. One instance per host, each
 * answering as its own account, so a test can tell which host a credential came from.
 */
@Factory
public class FakeHosts {
  @Bean
  @Profile(Wiring.TEST_PROFILE)
  public BitbucketClient bitbucket() {
    return new FakeRepositoryClient("agency-test-bitbucket");
  }

  @Bean
  @Profile(Wiring.TEST_PROFILE)
  public GitHubClient github() {
    return new FakeRepositoryClient("agency-test");
  }

  @Bean
  @Profile(Wiring.TEST_PROFILE)
  public GitLabClient gitlab() {
    return new FakeRepositoryClient("agency-test-gitlab");
  }
}
