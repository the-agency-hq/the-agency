/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module org.testng;

@Test
public class MainTest extends BaseTest {
  @Test
  public void getSlash() {
    test.get("/")
        .assertRedirect(303, "/app/organizations/");
  }
}
