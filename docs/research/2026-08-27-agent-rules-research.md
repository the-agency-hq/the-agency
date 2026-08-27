# How coding agents load rules, and how sync tools distribute them

Research for the Brief translation design, 2026-08-27. Every row was checked against official documentation or
source on that date; items marked **unverified** were not.

## 1. Why `AGENTS.md` cannot be a Brief output

[agents.md](https://agents.md/) defines the file as the maintainers' own committed, root-level "living
documentation" for agents ("Create an AGENTS.md file at the root of the repository"; OpenAI's repository holds 88
nested ones). The Handler treats an unmanaged file at a planned path as a conflict and skips the Location, and
`--force` overwrites it — so a Brief that emits `AGENTS.md` either stalls every Location that already has one or
destroys the team's file.

## 2. Codex: every channel for always-on instructions

| Mechanism | Verdict | Detail |
|---|---|---|
| `@import` / include in `AGENTS.md` | No | No syntax exists; `agents_md.rs` pushes raw bytes. Open requests [#6038](https://github.com/openai/codex/issues/6038), [#17401](https://github.com/openai/codex/issues/17401). |
| `project_doc_fallback_filenames` | No | Consulted only when a directory has neither `AGENTS.override.md` nor `AGENTS.md`; one file per directory. |
| Nested `AGENTS.md` (e.g. `.agency/AGENTS.md`) | No | Only directories on the project-root → cwd path are read. |
| `AGENTS.override.md` | No | Replaces `AGENTS.md` in that directory rather than adding to it. |
| Always-on skill | No | Only name and description load at start; `agents/openai.yaml` has no pin/preload field. |
| `.codex/rules/*.rules` | No | Starlark exec policy, not instructions. |
| `model_instructions_file` / `instructions` | Avoid | Replaces the base system prompt; docs say "STRONGLY DISCOURAGED". |
| `developer_instructions` in `.codex/config.toml` | **Yes** | "Additional developer instructions injected into the session"; a separate `developer` message, additive to `AGENTS.md`; allowed at project level; needs a trusted project. |
| `SessionStart` / `UserPromptSubmit` hook | **Yes** | Hook stdout or `additionalContext` "is added as extra developer context"; `.codex/hooks.json`; matcher `startup\|resume\|compact`; ~2,500-token default cap per message (`additionalContextLimit`); needs a trusted project; visible in the TUI transcript. |

Sources: [AGENTS.md guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md),
[config reference](https://learn.chatgpt.com/docs/config-file/config-reference.md),
[hooks](https://learn.chatgpt.com/docs/hooks), [skills](https://learn.chatgpt.com/docs/build-skills),
`codex-rs/core/src/agents_md.rs`, `codex-rs/core/src/hook_runtime.rs`.

## 3. Per-agent rules mechanisms

"Agency-owned location" means a place a third party can write always-on rules without touching the project's root
instruction file.

| Agent | Agency-owned always-on location | Path scoping | Root file(s) read | Root imports | Config key for extra files |
|---|---|---|---|---|---|
| Claude Code | `.claude/rules/**/*.md` | `paths:` globs | `CLAUDE.md`, `.claude/CLAUDE.md` | `@path` | — |
| Cursor | `.cursor/rules/**/*.mdc`, `alwaysApply: true` | `globs:` | `AGENTS.md` (root + nested), `CLAUDE.md` | — | — |
| Windsurf (Devin Desktop) | `.devin/rules/*.md` (`.windsurf/rules/` fallback), `trigger: always_on` | `trigger: glob` + `globs:` | `AGENTS.md` (nested = glob for that dir) | — | — |
| Cline | `.clinerules/*.md` (flat) | `paths:` | `AGENTS.md`, `.cursorrules`, `.windsurfrules` | — | — |
| Roo Code | `.roo/rules/**`, `.roo/rules-{mode}/` | — | `AGENTS.md` | — | — (project reportedly shut down 2026-05; unverified) |
| Kilo Code | `.kilocode/rules/*.md` (legacy auto-load); `.kilo/rules/` only if listed | — | `AGENTS.md`, `CLAUDE.md`, `CONTEXT.md` | — | `kilo.jsonc` `instructions` |
| Kiro | `.kiro/steering/*.md`, `inclusion: always` | `fileMatch` + `fileMatchPattern` | `AGENTS.md` (anywhere) | `#[[file:…]]` in steering | — |
| GitHub Copilot | `.github/instructions/*.instructions.md`, `applyTo: "**"` | `applyTo` | `.github/copilot-instructions.md`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` | CLI: `@path` | VS Code `chat.instructionsFilesLocations` |
| Augment / Auggie | `.augment/rules/*.md`, `type: always_apply` | — (`agent_requested`) | `AGENTS.md`, `CLAUDE.md`, `.augment-guidelines` | — | — |
| Antigravity | `.agents/rules/*.md` (legacy `.agent/rules/`), 12k chars each | UI modes incl. glob | `GEMINI.md`, `AGENTS.md` (CLI) | `@filename` | — |
| Gemini CLI | none | — | `GEMINI.md` hierarchy | `@./path.md` | `.gemini/settings.json` `context.fileName` (array) |
| Qwen Code | `.qwen/QWEN.local.md` (single file) | — | `QWEN.md`, `AGENTS.md` | `@path` | `.qwen/settings.json` `context.fileName` |
| OpenCode | none auto-discovered | — | `AGENTS.md` → `CLAUDE.md` | — | `opencode.json` `instructions` (globs; additive) |
| Amp | none | `globs:` in `@`-mentioned files | `AGENTS.md` → `AGENT.md` → `CLAUDE.md` | `@doc/style.md`, `@.cursor/rules/*.mdc` | — |
| Zed | none | — | first of `.rules`, `.cursorrules`, …, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` | — | — |
| JetBrains Junie | `.junie/rules/*.md` + `.junie/playbook.md` — only when `.junie/AGENTS.md` is absent | — | `.junie/AGENTS.md`, else root `AGENTS.md` | — | — |
| Kimi Code CLI | `.kimi-code/AGENTS.md` (one file per directory on the root → cwd chain; additive) | — | `AGENTS.md` | — | — |
| Factory Droid | `.factory/AGENTS.md`, `.agents/AGENTS.md`, `.agent/AGENTS.md` (one file per dir; additive) | — (nested lazy) | `AGENTS.md`, `CLAUDE.md` | — | — |
| Goose | none (env `CONTEXT_FILE_NAMES` only) | — | `AGENTS.md`, `.goosehints` | `@path.md` | — |
| Warp / Oz | none | — | `AGENTS.md` (or legacy `WARP.md`) | — | — |
| Mistral Vibe | none (nested `AGENTS.md` lazy) | — | `AGENTS.md` | — | `.vibe/config.toml` `system_prompt_id` (replaces) |
| OpenHands | flat `*.md` without `triggers:`/`paths:` in `.agents/skills/` | `paths:` | `.cursorrules`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` | — | — |
| Aider | none | — | none auto-loaded | — | `.aider.conf.yml` `read:` |
| Codex | `.codex/config.toml` `developer_instructions`; `.codex/hooks.json` `SessionStart` | — | `AGENTS.md` (root → cwd) | — | `project_doc_fallback_filenames` (absent-only) |

Sources: Claude [memory](https://code.claude.com/docs/en/memory); Cursor [rules](https://cursor.com/docs/context/rules);
Windsurf [memories](https://docs.devin.ai/desktop/cascade/memories); Cline [rules](https://docs.cline.bot/customization/cline-rules);
Roo [custom instructions](https://roocodeinc.github.io/Roo-Code/features/custom-instructions); Kilo [rules](https://kilo.ai/docs/customize/custom-rules);
Kiro [steering](https://kiro.dev/docs/steering/); Copilot [custom instructions](https://docs.github.com/en/copilot/reference/custom-instructions-support);
Augment [rules](https://docs.augmentcode.com/cli/rules); Antigravity [rules](https://antigravity.google/docs/rules-workflows);
Gemini [GEMINI.md](https://geminicli.com/docs/cli/gemini-md/); Qwen [memory](https://qwenlm.github.io/qwen-code-docs/en/users/features/memory/);
OpenCode [rules](https://opencode.ai/docs/rules/); Amp [AGENTS.md](https://ampcode.com/docs/customize/agents-md);
Zed [instructions](https://zed.dev/docs/ai/instructions); Junie [guidelines](https://junie.jetbrains.com/docs/guidelines-and-memory.html);
Kimi [agents](https://moonshotai.github.io/kimi-code/en/customization/agents.html); Factory [AGENTS.md](https://docs.factory.ai/harness/agents-md);
Goose [goosehints](https://block.github.io/goose/docs/guides/context-engineering/using-goosehints/); Warp [rules](https://docs.warp.dev/agent-platform/capabilities/rules/);
Vibe [configuration](https://docs.mistral.ai/vibe/code/cli/configuration); OpenHands [repo skills](https://docs.openhands.dev/overview/skills/repo);
Aider [conventions](https://aider.chat/docs/usage/conventions.html).

## 4. How sync tools handle single-file agents

Every tool surveyed assumes it *owns* the project's instruction file — none faces the Agency's constraint of a
background daemon that must not touch team files.

| Pattern | Tools |
|---|---|
| Overwrite the root file (usually git-ignored, with a "generated" header and `.bak`) | Ruler, rulesync, agent_sync, ai-rules-sync, ai-rulez, dotagent, airul |
| Managed block between markers (`<!-- apm:start -->` … `<!-- apm:end -->`); unmarked files skipped | Microsoft APM (opt-in), eugeniughelbur/agents-md |
| Separate files plus a reference line in the root file (`@.agents/memories/x.md` with `applyTo` globs; Claude's `@AGENTS.md`) | rulesync (TOON section), Claude Code docs, Gemini `@import` |
| Fold scoped rules into the single file with a prose scope note (`> **Applies to:** glob`) | rulesync codexcli, APM `--single-agents`, PanisHandsome agentsync |
| Nested per-directory `AGENTS.md` for scoping | APM distributed compile, rulesync `subprojectPath` |
| Symlink | agentlink, dallay/agentsync, lbb00/ai-rules-sync |

Vendors themselves converge on cross-reading rather than converting: Cursor, Windsurf, Copilot, Kilo, Kiro, Cline,
Augment and OpenCode all read `AGENTS.md` (and often `CLAUDE.md`); Claude Code's bridge is `@AGENTS.md` in
`CLAUDE.md`; Junie offers a one-time import into `.junie/AGENTS.md`.

Sources: [Ruler](https://github.com/intellectronica/ruler), [rulesync](https://github.com/dyoshikawa/rulesync)
(`src/features/rules/codexcli-rule.ts`, `agentsmd-rule.ts`), [APM compile](https://microsoft.github.io/apm/reference/cli/compile/)
(`src/apm_cli/compilation/agents_compiler.py`), [vercel-labs/skills](https://github.com/vercel-labs/skills),
[agent_sync](https://github.com/yelmuratoff/agent_sync), [ai-rulez](https://github.com/Goldziher/ai-rulez),
[dotagent](https://github.com/johnlindquist/dotagent), [agentlink](https://github.com/snapsynapse/agentlink).

## 5. What this means for the Agency

1. Rules belong in each agent's **native rules directory**, one file per source rule. Path scoping translates
   (`paths:` → `globs:` / `applyTo` / `fileMatchPattern` / `trigger: glob`), and because files stay separate the
   Handler's per-file Mission Type filtering keeps working — no prose scope notes needed.
2. Codex has no rules directory; `developer_instructions` in `.codex/config.toml` is the additive channel. Folding
   is unavoidable there, so scope notes stay for Codex only.
3. A generated `.agents/AGENTS.md` (never root) serves Factory natively and gives the AGENTS.md-only agents (Amp,
   Zed, Warp, Vibe, Goose, OpenCode, Gemini, Qwen, Aider) a file the **team** can reference from their own root
   `AGENTS.md` with one line — `@.agents/AGENTS.md` where imports exist, a prose "read … first" elsewhere. The
   Agency never writes the root file.
