/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module java.base;

/**
 * One source agent — an {@code agents/**.md} file in Claude Code's subagent format, YAML frontmatter over a Markdown
 * system prompt — as {@link SourceTree#agents()} reads it, reduced to the three things every Agent's format has: a
 * name, a description and the prompt. The other frontmatter fields ({@code model}, {@code tools},
 * {@code permissionMode}, ...) are Claude Code's own and are dropped, so a translated agent inherits its session's
 * settings.
 *
 * @param path         The source path.
 * @param stem         The file name without {@code .md}; the output file name everywhere.
 * @param name         The {@code name} field, or the stem when absent.
 * @param description  The {@code description} field.
 * @param body         The system prompt, stripped.
 * @param missionTypes The Mission Types, canonical form.
 */
public record AgentDefinition(String path, String stem, String name, String description, String body,
                              List<String> missionTypes) {
  public static final String DIRECTORY = "agents";
  private static final String MARKDOWN = """
      %s
      %s
      """;

  /**
   * @return A mutable, ordered {@code name} and {@code description}, for a Translator to extend before writing.
   */
  public Map<String, Object> frontmatter() {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("name", name);
    fields.put("description", description);
    return fields;
  }

  /**
   * @param frontmatter The frontmatter fields, in order.
   * @return A Markdown agent file: the frontmatter, then the prompt.
   */
  public String markdown(Map<String, Object> frontmatter) {
    return MARKDOWN.formatted(YAML.frontmatter(frontmatter), body);
  }
}
