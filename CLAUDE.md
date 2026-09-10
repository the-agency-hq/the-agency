# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The Agency web application: it authors, versions, and distributes Briefs (from GitHub or GitLab repositories) to Handlers.
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
| `latte bundle`             | Build a self-contained runtime bundle into `build/bundle`                       |
| `latte deploy`             | Bundle and deploy to Railway via `railway up` (needs a linked Railway project)   |
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
  `Main.REQUIRED_CONFIG`. Brief source credentials (`github.clientId`/`github.clientSecret`, `gitlab.clientId`/
  `gitlab.clientSecret`) are optional: a kind of source is offered only when its credentials are configured
  (`SourceCatalog`), and with none configured the Sources page says so. Everything except connecting a source
  works without them.

## Architecture

**Wiring.** Dependency injection is Avaje Inject (`io.avaje.inject`, annotation-processed at compile time; the
generated `AgencyModule` is registered in `module-info.java`). Every controller, service, repository, and
`OrganizationSecurity` is a `@Singleton` wired by its constructor; the two `OIDC<User>` profiles are `@Named`
`ssr` and `api` (`Wiring.SSR`, `Wiring.API`). `Wiring` is the one `@Factory`: it derives the OIDC profiles,
`Cookies`, `Database`, `DSLContext`, the real `GitHubClient` and `GitLabClient`, and the JTE templates from the `Configuration`.
`Main` builds the `Configuration` (which files it layers is its decision), supplies it to the `BeanScope` along
with a test's fake host clients if there are any, registers the scope as Web's `Injector`
(`web.injector(injector::get)`), and builds the route table with `web.inject(Controller.class, Controller::method)`,
which resolves the controller from the scope on every request. Building the scope is startup: migrations,
FusionAuth discovery, the poller thread (`@PostConstruct`). Closing it is shutdown (`@PreDestroy` on the poller, the
`Database` bean's destroy method), reached from both `Web`'s shutdown task and `Main.close()`, so `Main.shutdown` is
idempotent. `Database` also keeps a static `instance()` for the application's database; a test may construct its
own `new Database(config)` for a scratch database.

**Two authentication boundaries, two FusionAuth Applications.** Routes under `/api` (the Briefing API, called by
Handler daemons) validate JWTs against the Handler Application; routes under `/app` (the admin UI) use a browser
session against The Agency Application. The audience (`aud`) check is the boundary — a Handler token cannot open
the admin UI and vice versa. Middleware is installed on the `/api` and `/app` prefixes, so new routes are
authenticated by construction. The server binds loopback in development (there is no local TLS listener);
`runtime.mode=production` binds every interface behind Railway's TLS-terminating edge
(`docs/design/2026-08-18-railway-deploy-design.md`).

**Memberships are the authorization.** The `members` table (Organization × FusionAuth user id, role
OWNER/CONTRIBUTOR, state ACTIVE/PENDING) gates everything: `OrganizationSecurity` on the `/app/organizations`
prefix requires a membership row for any `{organizationId}` route (denials silently 303 to the listing), per-route
`HasRole(OWNER)` gates the management pages, and both APIs serve only the caller's ACTIVE memberships. Creating an
Organization seats the creator as ACTIVE OWNER. Invitations go through FusionAuth (`MembershipService`): known
emails get the invitation email template, unknown ones get a FusionAuth registration whose set-password email is
the invitation — templates live in `src/main/fusionauth/kickstart/emails/`, and Mailcatcher (in the compose stack,
http://localhost:1080) receives them locally.

**Brief pipeline.** An Organization connects a Brief source from its Sources page (`/sources`, Owner-only), which
lists one card per kind the server is configured for (`SourceCatalog.available()`). `brief_sources` holds one row
per Organization: a `type` (`BriefSourceType`, which also carries the kind's URL slug), the identity that type is
unique by in `source` (the repository as its host names it — `owner/repository` on GitHub, `group/project` on
GitLab — case-insensitive via the `(type, LOWER(source))` index), and the whole configuration — credential
included — as one JSONB document in `source_config`, a `BriefSourceConfig` sealed hierarchy discriminated by `type`
(`GitHubConfig`, `GitLabConfig`; the interface itself exposes `connection()`, `fullName()`, `branch()` and the
`with...` methods, so everything above the row is host-neutral). `RepositorySourceController` runs the OAuth
handshake for every kind under `/app/oauth/{slug}/start|callback` (plus GitHub's install/setup pair); the callback
creates the row connected and unregistered (`SourceLinkService.link`); the picker under `/sources/{slug}` registers
the repository (`OrganizationService.connect` swaps the repository, resets the poll history, and keeps the
credential). `PollerService` (a background thread, interval `poller.intervalSeconds`, disabled via
`poller.enabled=false`) skips unregistered sources, reports sources of an unconfigured kind without touching their
credential, and polls the host through the `RepositoryClient` the catalog resolves for the source's type.
`BriefBuilder` hands the repository tree to every `Translator` in `service/translation/` and unions their files; a
duplicate output path fails the build. `StandardTranslator` owns `.agents/` (skills, subagents, and the folded rules
in `.agents/AGENTS.md` — never the root `AGENTS.md`, which is the team's own file); every other Translator owns one
Agent's dot-directory and writes one file per rule into that Agent's native rules directory (`Rule`), one file per
subagent (`AgentDefinition`), and its `<agent>/` escape hatch verbatim. Codex has no rules directory, so its rules
go into `.codex/config.toml` as `developer_instructions`. `docs/research/2026-08-27-agent-rules-research.md` records
what each Agent reads. Unchanged content checksums skip the insert. Versions are immutable and insert-only;
`POST /api/v1/briefing` serves them, and its wire contract is frozen by the already-shipped Handler. Adding an Agent
type is one `Translator` (with `agent()` and `reads(path)`), an `Agent` enum constant, and an entry in
`BriefBuilder.TRANSLATORS`.

**Agent selection.** `organizations.agents` (JSONB, `NULL` = All) holds the Agents an Organization uses; it rides
on `Organization.agents` into the stored Brief document, so it feeds the checksum. The stored Brief always holds
every Translator's output; `BriefReducer` narrows it to the files the selected Agents read (each `Translator.reads`)
only on the way out of the Briefing API. Changing the selection (`OrganizationService.updateAgents`) republishes the
latest Brief as a new version in one transaction with the row update, so Handlers resync on their next poll.

**Build gotcha.** `@JSON` is `SOURCE`-retained, and the compile is incremental: after editing a `@JSON` record that
references another `@JSON` type, a stale `not @JSON-annotated` error means run `latte clean` first.

**Host seam.** `RepositoryClient` (package `source`) is the one contract for everything the app asks a repository
host: the two OAuth grants, the account, the repository listing, a ref's head, one file, and the whole tree at a
commit. `GitHubClient` and `GitLabClient` are marker sub-interfaces so the scope holds one bean per host;
`GitHubHTTPClient` and `GitLabHTTPClient` are the real implementations, and `SourceCatalog` maps a
`BriefSourceType` to its client, decides which kinds are configured, builds the authorize URL, and creates a
fresh source of a kind from configuration (`unregistered`). A source describes itself for the admin UI
(`BriefSourceConfig.details()`/`url()`); the Organization's page renders those rows and assumes nothing about
what a source is. The hosts are the app's only outbound dependencies and the one thing tests
fake — `FakeRepositoryClient` (one instance per host) is injected into `Main`'s constructor; the shared
`RepositoryConnectionTestBase` runs the whole handshake suite once per kind. Adding a source type is a
`BriefSourceType` constant, a `BriefSourceConfig` subtype plus its `unregistered` branch, a migration widening
`brief_sources_ck_type`, a marker interface and HTTP client, a `Wiring` bean and `Main` constructor parameter, the
catalog's entries (configured keys, authorize URL, `unregistered`), a card description and icon in
`sources.jte`/`source-icon.jte`, and a subclass of the connection test base.

**Database.** SQL migrations in `src/main/resources/db` are applied by the app at startup (Latte Database
`Migrator`). The jOOQ classes in `src/main/java/dev/theagencyhq/agency/db/jooq/` are generated — never hand-edit
them; change a migration and run `latte codegen`. Data access is one repository per model in `db/` —
`OrganizationRepository`, `BriefSourceRepository`, `BriefRepository` (insert-only: `create` assigns the version),
and `MemberRepository` — each holding only core operations (`create`, `update`, `upsert`, `delete`, `findById`,
`findBy...`, `findAll...`) over whole rows, all built on `Database.dsl()`. Business decisions live in the services,
which read a row, change it through the model's `with...` methods, and write it back. Transactions belong to
`Database` — jOOQ's `ThreadLocalTransactionProvider` binds one to the calling thread, so a service composes
repository calls inside `Database.transaction`. The poller re-reads a source before recording a status so it never
puts a stale credential back.

**Frontend.** JTE templates in `web/templates`, view models in `model/view/`; Web's base directory is `web`
(`Main.BASE_DIR`), so static files are served from `web/static` and messages are read from `web/messages`. Latte's `FlashMessages` middleware is installed globally and the
layout renders every queued `Flash` message as the admonition its type names (`info`, `success`, or `warning`), so a
handler that redirects queues its notice with `new Flash(req).addMessage(type, message)` and the page it lands on shows
it once. The text a handler queues comes from `web/messages` (Latte `Messages`: properties files mirroring the request
path, so `web/messages/app/oauth/index.properties` serves every `/app/oauth/...` route); tests read the same files with
`new Messages(Main.BASE_DIR, path)`. Tailwind compiles `src/main/css/app.css` → `web/static/css/app.css` (a build artifact — don't
edit the output).

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
