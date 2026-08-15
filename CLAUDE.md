# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The Agency web application: it authors, versions, and distributes Briefs (from GitHub repositories) to Handlers.
Java 25 with JPMS modules, built with Latte (`project.latte`), running on the Latte Java stack
(`org.lattejava:web`, `http`, `database`, `fusionauth`, `jwt`). PostgreSQL via jOOQ + HikariCP, JTE templates,
Tailwind CSS, FusionAuth for all authentication.

## Commands

| Command                    | What it does                                                                    |
|----------------------------|---------------------------------------------------------------------------------|
| `latte build`              | Compile, JAR, and build Tailwind CSS                                            |
| `latte test`               | Run all tests (recreates the test database first)                               |
| `latte test --test=<Name>` | Run one test class — simple name, fully-qualified name, or substring match      |
| `latte test --onlyFailed`  | Re-run only the tests that failed last run                                      |
| `latte run`                | Build and start the server on http://localhost:8080                             |
| `latte codegen`            | Regenerate jOOQ classes from the migrations (uses a scratch `agency_schema` DB) |
| `latte main-database`      | Create/recreate the main database                                               |
| `latte tailwind`           | Tailwind in watch mode                                                          |

### Prerequisites for `test` and `run`

- Local PostgreSQL.
- FusionAuth: `cd src/main/fusionauth && docker compose up -d` (port 9016, user `admin@theagencyhq.dev` /
  `password`). Kickstart only provisions an empty database — after changing `kickstart.json`, run
  `docker compose down -v` first. The app **fails to start** if FusionAuth is down (it runs OIDC Discovery and
  fetches the JWKS during construction).
- Config overrides live in `~/.config/the-agency-hq/the-agency/config.properties`. Required keys are listed in
  `Main.REQUIRED_CONFIG`. `github.clientId`/`github.clientSecret` ship as `replace-me`; everything except the
  GitHub connect flow works without a real GitHub App.

## Architecture

**Wiring.** `Main` builds the two OIDC profiles, the JTE templates, and the route table, then calls
`Services.initialize(config, gitHubClient)`. `Services` is a static singleton registry — every service is created
there, in dependency order, and `Services.shutdown()` is idempotent because it runs from multiple shutdown paths.

**Two authentication boundaries, two FusionAuth Applications.** Routes under `/api` (the Briefing API, called by
Handler daemons) validate JWTs against the Handler Application; routes under `/app` (the admin UI) use a browser
session against The Agency Application. The audience (`aud`) check is the boundary — a Handler token cannot open
the admin UI and vice versa. Middleware is installed on the `/api` and `/app` prefixes, so new routes are
authenticated by construction. The server binds loopback only (no TLS listener).

**Memberships are the authorization.** The `members` table (Organization × FusionAuth user id, role
OWNER/CONTRIBUTOR, state ACTIVE/PENDING) gates everything: `OrganizationSecurity` on the `/app/organizations`
prefix requires a membership row for any `{organizationId}` route (denials silently 303 to the listing), per-route
`HasRole(OWNER)` gates the management pages, and both APIs serve only the caller's ACTIVE memberships. Creating an
Organization seats the creator as ACTIVE OWNER. Invitations go through FusionAuth (`MembershipService`): known
emails get the invitation email template, unknown ones get a FusionAuth registration whose set-password email is
the invitation — templates live in `src/main/fusionauth/kickstart/emails/`, and Mailcatcher (in the compose stack,
http://localhost:1080) receives them locally.

**Brief pipeline.** An Organization connects a GitHub repository through the admin UI (GitHub App OAuth; the
credential is stored in columns on the `organizations` row). `PollerService` (a background thread, interval
`poller.intervalSeconds`, disabled via `poller.enabled=false`) polls each source through `GitHubClient`,
`BriefBuilder` turns the repository tree into a Brief file list, and unchanged content checksums skip the insert.
Versions are immutable and insert-only; `POST /api/v1/briefing` serves them, and its wire contract is frozen by
the already-shipped Handler.

**GitHub seam.** `GitHubClient` is an interface; `GitHubHTTPClient` is the real REST implementation. It is the
app's only outbound dependency and the one thing tests fake — `FakeGitHubClient` is injected into `Main`'s
constructor. Everything else in tests is real.

**Database.** SQL migrations in `src/main/resources/db` are applied by the app at startup (Latte Database
`Migrator`). The jOOQ classes in `src/main/java/dev/theagencyhq/agency/db/jooq/` are generated — never hand-edit
them; change a migration and run `latte codegen`.

**Frontend.** JTE templates in `web/templates`, view models in `model/view/`. Tailwind compiles
`src/main/css/app.css` → `web/static/css/app.css` (a build artifact — don't edit the output).

**Modules.** The app and the tests are JPMS modules with their own `module-info.java`. Prefer `import module`
over class imports. Test packages must be `opens ... to org.testng;` in the test module-info.

## Tests

TestNG; test classes must end in `Test`. HTTP tests start a real `Main` on a test-only port and authenticate
through real FusionAuth authorization-code flows (once per suite, per Application), against the real local
`the_agency_test` PostgreSQL database. Test-only config is layered from `src/test/resources/config.properties`.

## Conventions

`.claude/rules/` holds the project rules (loaded automatically): code conventions (2-space indent, full-uppercase
acronyms like `GitHubHTTPClient`, alphabetized members/imports/module clauses, module imports), error-message
formatting (runtime values in `[square brackets]`), the SPDX copyright header every Java file starts with, and the
git workflow (feature branches, Conventional Commits, squash merge to `main`, delete merged branches).

## Docs

- `docs/idea.md` — the product idea.
- `docs/design/` — dated design docs; later docs supersede parts of earlier ones (each says what it replaces).
- `docs/implementation/` — implementation notes per design.
- `README.md` — FusionAuth local setup, GitHub App requirements, connect-a-repository workflow.
