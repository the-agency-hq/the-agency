/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests.service.translation;

import module dev.theagencyhq.agency;
import module java.base;
import module org.testng;

import static org.testng.Assert.*;

@Test
public class FrontmatterTest {
  @Test
  public void blockListAndFlowList() {
    var parsed = Frontmatter.parse("""
        ---
        paths:
          - "**/*.java"
          - project.latte
        tools: [Read, "Grep", 'Bash']
        empty: []
        ---
        Body
        """);

    assertEquals(parsed.list("paths"), List.of("**/*.java", "project.latte"));
    assertEquals(parsed.list("tools"), List.of("Read", "Grep", "Bash"));
    assertEquals(parsed.list("empty"), List.of());
    assertEquals(parsed.list("missing"), List.of());
  }

  @Test
  public void blockScalars() {
    var parsed = Frontmatter.parse("""
        ---
        literal: |
          line one
            indented
          line three
        folded: >
          folded one
          folded two

          new paragraph
        after: yes
        ---
        """);

    assertEquals(parsed.scalar("literal"), "line one\n  indented\nline three");
    assertEquals(parsed.scalar("folded"), "folded one folded two\nnew paragraph");
    assertEquals(parsed.scalar("after"), "yes");
  }

  @Test
  public void bodyIsEverythingAfterTheClosingFence() {
    var parsed = Frontmatter.parse("---\nname: x\n---\n\nFirst line\n\nSecond\n");
    assertTrue(parsed.present());
    assertEquals(parsed.body(), "\nFirst line\n\nSecond\n");
  }

  @Test
  public void nestedMappingsAndCommentsAreSkippedNotRejected() {
    var parsed = Frontmatter.parse("""
        ---
        # A comment
        name: agent
        hooks:
          PreToolUse:
            - matcher: Bash
        description: after the nesting
        ---
        """);

    assertEquals(parsed.scalar("name"), "agent");
    assertEquals(parsed.scalar("description"), "after the nesting");
    assertEquals(parsed.scalar("hooks"), "");
    assertEquals(parsed.list("hooks"), List.of());
  }

  @Test
  public void noFrontmatter() {
    var parsed = Frontmatter.parse("# Title\n\nBody\n");
    assertFalse(parsed.present());
    assertEquals(parsed.body(), "# Title\n\nBody\n");
    assertNull(parsed.scalar("name"));
  }

  @Test
  public void openingFenceWithoutAClosingOneIsNotFrontmatter() {
    var text = "---\nname: x\nno closing fence\n";
    var parsed = Frontmatter.parse(text);
    assertFalse(parsed.present());
    assertEquals(parsed.body(), text);
  }

  @Test
  public void quotedScalars() {
    var parsed = Frontmatter.parse("""
        ---
        plain: Reviews code: carefully
        double: "Say \\"hi\\"\\nthen stop"
        single: 'It''s quoted'
        ---
        """);

    assertEquals(parsed.scalar("plain"), "Reviews code: carefully");
    assertEquals(parsed.scalar("double"), "Say \"hi\"\nthen stop");
    assertEquals(parsed.scalar("single"), "It's quoted");
  }
}
