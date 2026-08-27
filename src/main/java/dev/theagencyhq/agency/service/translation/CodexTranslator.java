/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.translation;

import module dev.theagencyhq.agency;
import module java.base;

/**
 * Produces what only Codex reads. Skills reach Codex through the {@link StandardTranslator}; this one carries the
 * agents, the rules and the {@code codex/} escape hatch.
 *
 * <p>Each source agent becomes a {@code .codex/agents/<stem>.toml} custom agent with the three fields Codex
 * requires: {@code name}, {@code description} and {@code developer_instructions}.
 *
 * <p>Codex has no rules directory, reads {@code AGENTS.md} only along the path from the project root to the working
 * directory, and has no include syntax — so the rules go into {@code .codex/config.toml} as
 * {@code developer_instructions}, which Codex injects as a separate developer message alongside whatever
 * {@code AGENTS.md} the team keeps. When the escape hatch carries its own {@code codex/config.toml}, the key is
 * appended to it rather than colliding with it. Codex loads a project's {@code .codex/} only after the developer has
 * trusted the project.
 */
public class CodexTranslator implements Translator {
  public static final String AGENTS_ROOT = ".codex/agents";
  public static final String CONFIG_FILE = "config.toml";
  public static final String DEVELOPER_INSTRUCTIONS_KEY = "developer_instructions";
  public static final String ESCAPE_HATCH = "codex";
  public static final String OUTPUT_ROOT = ".codex";
  private static final String AGENT = """
      name = %s
      description = %s
      developer_instructions = %s
      """;
  private static final String CONFIG = """
      developer_instructions = %s
      """;
  private static final String CONFIG_APPENDED = """
      %s

      developer_instructions = %s
      """;
  private static final Pattern DECLARES_KEY = Pattern.compile("(?m)^\\s*" + DEVELOPER_INSTRUCTIONS_KEY + "\\s*=");

  @Override
  public List<BriefFile> translate(SourceTree source) {
    var files = new ArrayList<BriefFile>();
    for (var agent : source.agents()) {
      var content = AGENT.formatted(TOML.basicString(agent.name()), TOML.basicString(agent.description()),
          TOML.multilineBasicString(agent.body()));
      files.add(source.generated(AGENTS_ROOT + "/" + agent.stem() + ".toml", content, agent.missionTypes()));
    }

    var rules = source.rules();
    var hatchConfig = ESCAPE_HATCH + "/" + CONFIG_FILE;
    var hatch = source.under(ESCAPE_HATCH);
    for (var path : hatch) {
      // The generated config below absorbs the escape hatch's own, so it is not copied on its own as well.
      if (rules.isEmpty() || !path.equals(hatchConfig)) {
        files.add(source.copy(path, OUTPUT_ROOT + "/" + SourceTree.remainder(path, ESCAPE_HATCH)));
      }
    }

    if (!rules.isEmpty()) {
      var existing = hatch.contains(hatchConfig) ? source.text(hatchConfig) : "";
      if (DECLARES_KEY.matcher(existing).find()) {
        throw new BriefBuildException("The escape hatch file [" + hatchConfig + "] sets [" + DEVELOPER_INSTRUCTIONS_KEY
                                      + "], which the translated rules also set. Move its content into the rules "
                                      + "directory instead.");
      }

      var instructions = TOML.multilineBasicString(Rule.toRulesDocument(rules, false));
      var content = existing.isBlank()
          ? CONFIG.formatted(instructions)
          : CONFIG_APPENDED.formatted(existing.stripTrailing(), instructions);
      files.add(source.generated(OUTPUT_ROOT + "/" + CONFIG_FILE, content, List.of()));
    }

    return files;
  }
}
