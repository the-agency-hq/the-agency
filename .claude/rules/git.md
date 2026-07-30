---
paths:
  - "**/*"
---

# Git

When using Git for the project, follow these guidelines.

## Branching

Never commit directly to `main`. All work happens on a feature branch created from `main`; `main` only ever receives changes through a squash merge. This is a standard to follow by reading this file — it is intentionally not enforced by a hook.

Branch names should be descriptive and follow the format `<type>/<short-description>`. The `type` is the conventional commit type for the work, usually `feat`, `fix`, or `chore`.

## Squash merges

Always squash merge feature branches into `main`, so each unit of work lands as a single commit. Feature branches may be merged into other feature branches with a standard (non-squash) merge.

## Branch deletion

Always delete feature branches after merging them into another branch.

## Commit messages

Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `<type>[optional scope][!]: <description>`.

- Allowed types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.
- Mark a breaking change with `!` after the type/scope (e.g. `feat!: …`) and/or a `BREAKING CHANGE:` footer.
- Because feature branches are squashed, the **squash commit message** is what lands on `main` and must itself be a single well-formed Conventional Commit. The per-commit messages on a feature branch must also follow the format.
- Trailers such as `Co-Authored-By:` are allowed; only the subject line matters.

## Enforcement

None of the standards in this file are enforced mechanically — there is no `commit-msg` hook and `core.hooksPath` is not configured. The commit format, the branching rules, and the squash-merge and branch-deletion standards are all conventions to follow by reading this file.
