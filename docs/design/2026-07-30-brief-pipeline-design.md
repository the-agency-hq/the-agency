# The Agency — Brief Pipeline and Briefing API (Milestone 1)

Status: proposed
Date: 2026-07-30
Source: `docs/idea.md`
Consumer: `../handler` (already implemented — see `../handler/docs/design/2026-07-26-handler-core-sync-design.md`)

> **Partly superseded.** Both faces of the Agency authenticate against FusionAuth now. The Briefing API no longer
> validates a configured list of static bearer tokens but OAuth access tokens, and the admin UI is no longer
> unauthenticated but sits behind a browser session. That replaces decision 2, the `handler.tokens` half of §6,
> §10.1, and the premise of §11 — see `docs/design/2026-08-06-oauth-authentication-design.md`. Everything else here
> still describes the shipped system, including the whole of §10.2, which the change did not touch. §10.4 is still
> pending: entitlements remain "every Organization".

## 1. Purpose

The Agency is the central application that authors, versions, and distributes Briefs. This milestone builds the
spine: take a Brief's source files from a Git working tree on the local machine, turn them into a versioned Brief,
and serve that Brief to Handlers over the Briefing API.

The Handler is already built and its contract is frozen in shipped code. This milestone is therefore not a
greenfield design — it is an implementation of a contract that already has a working client. §3 states that
contract exactly; everything else exists to satisfy it.

## 2. Scope

**In scope**

- PostgreSQL schema for Organizations, Brief sources, and versioned Briefs
- A poller that runs `git pull` against each registered source Path and detects changes
- The Brief builder — source layout to Brief file list, including Mission Type resolution
- Brief versioning, content checksums, and immutable insert-only version history
- `POST /api/v1/briefing`, matching the Handler's contract byte for byte
- Bearer token validation against a configured token list *(superseded — OAuth access tokens, see the 2026-08-06
  design)*
- An admin UI: Organizations, source Paths, version history, per-file preview *(shipped unauthenticated; put
  behind a FusionAuth session on 2026-08-07, see that design)*

**Out of scope — later milestones**

- GitHub App integration. Replaced here by local Paths; the poller is deliberately shaped so a `GitSource`
  abstraction can gain a GitHub implementation without touching the builder or the API.
- Identity: FusionAuth OIDC login, users, Teams, per-user Organization membership, entitlement-derived
  `organizationIds`, and the `403` response. §10.4 records exactly where these land. *(Login arrived on
  2026-08-06/07 for both the API and the admin UI, along with a `User` record translated from the token. Teams,
  memberships, entitlements, and the `403` are all still pending.)*
- Content translation between agent types (concatenating rules into `AGENTS.md`, reshaping skills for Codex).
  Milestone 1 maps paths and never rewrites bytes.
- Audit logging (GRC), Brief approvals, states, scoping, layering, fleet and drift reporting — all explicitly
  deferred by `idea.md`.

**Platform**: macOS and Linux. PostgreSQL 18 locally.

## 3. Decisions made during design

| #  | Question                                | Decision                                                                                                                                                                                |
|----|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | Milestone 1 scope                       | Spine plus a minimal admin UI, including read-only Brief file preview.                                                                                                                  |
| 2  | Handler authentication                  | ~~Validate the bearer token against a list in the config file.~~ **Superseded 2026-08-06:** FusionAuth OAuth access tokens, validated against the provider's JWKS. Still no users, no memberships, no token table. |
| 3  | Entitlements                            | A valid token is entitled to **every** Organization in the database. `organizationIds` is therefore the full Organization list. §10.4 covers the migration to real entitlements.        |
| 4  | Brief translation depth                 | Path mapping only (§8.4). File bytes are never rewritten. The agent-type table is data, so adding an agent type is one entry.                                                           |
| 5  | Brief `checksum` semantics              | Agency-defined and opaque to the Handler (§9.2). `idea.md` says the Handler SHA-256s its stored JSON; the shipped Handler does not and never will. The shipped Handler wins.            |
| 6  | Organization `id` format                | UUID. The Handler requires the id to be a single path segment — it resolves it against its store root — and a UUID satisfies that with no validation burden on the author.              |
| 7  | Sources per Organization                | Exactly one. `idea.md`: "one Brief for each Organization (to start off let's keep things simple)". Enforced by a unique constraint on `brief_sources.organization_id`.                  |
| 8  | Git access                              | Shell out to the `git` CLI via `ProcessBuilder`. No JGit. Matches `architecture.md`'s no-deps preference and what the Handler already does for `git rev-parse`.                         |
| 9  | Rebuild that produces identical content | No new version. Compare the content checksum against the latest version and skip the insert (§9.3). Without this, a README commit forces every Handler on every machine to re-download. |
| 10 | Data access                             | jOOQ over HikariCP, mirroring `latte-java/app`, with the schema owned by Latte Database's `Migrator`.                                                                                   |
| 11 | `git pull` failure                      | Not fatal. Log a warning, record it on the source row, and build from the current `HEAD` anyway (§7.3). This is what lets a purely local repository with no remote work unchanged.      |
| 12 | Symlinks in a source tree               | Fail the build for that Organization, naming the link. Silently omitting a file would ship an incomplete Brief with no signal to its author.                                            |
| 13 | Response assembly                       | Parse each stored document into a `Brief` on the way out of the database and serialize the envelope through the generated codec (§10.3). The Agency writes no JSON by hand.             |

### 3.1 Deliberate deviations from `idea.md`

Flagged so they can be rejected:

1. **Brief checksum ownership** (decision 5). `idea.md` §API says the checksum "is based on the full JSON response
   from the original response the Handler received" and that "the Handler will SHA256 checksum the JSON file in the
   store". The shipped Handler treats the checksum as opaque and never computes one — `Brief.checksum` is parsed,
   stored, and echoed. Two further problems make `idea.md`'s version unimplementable as written: the checksum is a
   field *inside* the object it would have to cover, and it is described as covering the whole *response*, which
   contains multiple Briefs and so could never be attributed to one. The Agency defines it over Brief content
   (§9.2).
2. **`organizationIds` in the response.** Not in `idea.md`, which left revocation signalling as "an implementation
   detail". The Handler requires it as the complete entitled set. It is a hard requirement here, not an option.
3. **Content-addressed versioning** (decision 9). `idea.md` says the version increments "when a Brief changes at The
   Agency ... from the source", which read literally means every commit. Comparing content instead makes the version
   mean "the Brief is different", which is what every consumer actually wants.

## 4. Contract with the Handler — frozen

Read from the Handler's shipped records (`agency/BriefingRequest.java`, `agency/CurrentVersion.java`,
`agency/BriefingResponse.java`, `brief/Brief.java`, `brief/BriefFile.java`, `brief/Organization.java`). The Agency
must match these exactly. They are not negotiable within this milestone.

**Request** — `POST /api/v1/briefing`, `Authorization: Bearer <token>`, `Content-Type: application/json`:

```json
{
  "currentVersions": [
    { "organizationId": "42", "version": 73, "checksum": "..." }
  ]
}
```

**Response `200`**:

```json
{
  "organizationIds": ["42", "43"],
  "briefs": [
    {
      "checksum": "opaque-42-73",
      "organization": { "id": "42", "name": "FusionAuth" },
      "version": 73,
      "files": [
        {
          "path": ".claude/rules/foo.md",
          "encoding": "text",
          "mode": "r--------",
          "content": "For Claude",
          "checksum": "7b0464d7...",
          "missionTypes": ["Web", "Library"]
        }
      ]
    }
  ]
}
```

Properties the Handler relies on:

- **`organizationIds` is the complete entitled set, not a delta.** Any Organization the Handler holds in its store
  but which is absent from this list is treated as revoked and torn down. `briefs` carries only what changed.
- **A Brief's `checksum` is opaque.** Stored verbatim, echoed verbatim in the next `currentVersions`.
- **A file's `checksum` is SHA-256 of its decoded bytes, hex-encoded lowercase.** The Handler verifies this and
  refuses to store a Brief that fails, so an incorrect value here silently stalls every machine at the old version.
- **`encoding`** is `text` (content is the UTF-8 string) or `base64` (content decodes to raw bytes).
- **`mode` is symbolic, not octal.** Exactly nine `rwx-` characters in `ls -l` order (`r--------`, `rwxr-xr-x`),
  which the Handler passes to `PosixFilePermissions.fromString`. An octal mode throws there, so it stalls every
  machine the same way a bad checksum does. `setuid`/`setgid`/`sticky` (`s`/`S`/`t`/`T`) are rejected by the
  Handler because `PosixFilePermission` has no constant for them.
- Handler-side defaults when a field is absent: `encoding` = `text`, `mode` = `r--------`, `missionTypes` = `[]`.
  The Agency always emits all three explicitly; the defaults are a compatibility cushion, not a licence to omit.
- **The Handler lowercases `missionTypes` and the `checksum` on parse.** The Agency emits both already lowercased
  — `BriefFile` applies the same normalization the Handler does (§9.2) — so the two sides agree by construction
  rather than by each remembering to. Mission Types therefore go out canonical rather than in the author's original
  case, unlike the `["Web", "Library"]` in `idea.md`'s example.

**Status codes**:

| Status  | Meaning to the Handler                | Milestone 1 behavior                                                     |
|---------|---------------------------------------|--------------------------------------------------------------------------|
| `200`   | Body carries updated Briefs           | Sent when anything is stale or the asserted set differs (§10.2)          |
| `304`   | Every version and checksum is current | Sent only under the precise condition in §10.2                           |
| `401`   | Token invalid or expired              | Missing, malformed, or unknown bearer token                              |
| `403`   | Token valid, no entitlements at all   | **Never sent in milestone 1** — see §10.4                                |

## 5. Component map

One JPMS module, `dev.theagencyhq.agency`, following `web-conventions.md` (controllers in `controller`, singleton
services in `service` registered on `Services`, validation split into `service.validation`, view models suffixed
`View` in `model.view`).

```
dev.theagencyhq.agency
├── controller/   BriefingController, OrganizationController
├── db/           DatabaseService, jooq/ (generated)
├── error/        ValidationException, MissingException
├── model/        Organization, Brief, BriefFile, SourceSettings (@JSON),
│   │             BriefSource, SourceStatus, User
│   │             internal/ (generated codecs)
│   ├── api/      Request and response envelopes: BriefingRequest,
│   │             BriefingResponse, CurrentVersion                        (all @JSON)
│   │             internal/ (generated codecs)
│   └── view/     OrganizationsView, OrganizationDetailView, BriefVersionView, BriefFileView
├── service/      Services, OrganizationService, BriefSourceService, BriefService,
│                 BriefBuilder, GitService, PollerService, BriefingService,
│                 UserService, validation/
└──               Main
```

`User` and `UserService` are the two exceptions to the shape above, both added on 2026-08-06 with OAuth
authentication. `User` is in `model/` but is not a database row and carries no `@JSON` — it is built from the claims
of the access token on the request. `UserService` is in `service/` but is not a registered singleton: it is static,
because it holds nothing but the claim names. Both mirror `latte-java/app`.

`model/api/` holds only the request and response envelopes of `POST /api/v1/briefing` — the types that exist
because there is an HTTP endpoint. What those envelopes carry (`Brief`, `BriefFile`, `Organization`) lives in
`model/` alongside `BriefSource`: a Brief is a domain object that happens to be serialized, not
an artifact of the transport. `SourceSettings` sits there too, for the same reason — it models a file on disk.

`Organization` in particular stays in `model/`, because it is one type serving both roles rather than a domain record
with a wire twin. The Handler reads only `id` and `name` and parses non-strictly (`@JSON`'s `strict()` defaults to
`false`), so `insertInstant` and `updateInstant` ride along as keys it has no member for and ignores. `id` is a
`UUID` here and a `String` there, which agrees because a UUID serializes as a JSON string — and it satisfies the
Handler's requirement that the id be a single path segment, since it resolves the id against its store root.

Three units are pure and carry the design's risk, so each is testable with no database and no HTTP:

| Unit             | Input                              | Output                                | Depends on         |
|------------------|------------------------------------|---------------------------------------|--------------------|
| `BriefBuilder`   | a directory                        | an unpublished `Brief`                | the filesystem     |
| `GitService`     | a directory                        | commit SHA / pull outcome             | the `git` binary   |
| `BriefingService`| asserted versions                  | `BriefingOutcome` (sealed)            | the database       |

`PollerService`, `BriefingService` and the controllers are the ones that reach the database.

## 6. Configuration

`org.lattejava.web.Configuration`, mirroring `latte-java/app`: required keys are declared in `Main`, and values are
read from a chain of properties files with environment-variable overrides.

`~/.config/the-agency-hq/agency/config.properties`:

```properties
db.url=jdbc:postgresql://127.0.0.1:5432/agency
db.username=dev
db.password=dev
fusionauth.clientId=fa83bc7c-f1c5-48af-8ecb-6c09cf766d73
fusionauth.issuer=http://localhost:9016
poller.enabled=true
poller.intervalSeconds=60
```

- `db.url`, `db.username`, `db.password` — required.
- `fusionauth.clientId`, `fusionauth.issuer` — required. These replaced `handler.tokens` on 2026-08-06; see
  §6 of `docs/design/2026-08-06-oauth-authentication-design.md`.
- `poller.intervalSeconds` — optional, default `60`, clamped to a minimum of `5`.
- `poller.enabled` — optional, default `true`. Whether the poller thread starts. The tests set it to `false` and
  drive `IntervalThread.testRun()` explicitly so a background cycle can never race an assertion.

The port is a `Main` constructor parameter rather than a constant read at startup. `Main()` uses `PORT` (8080);
`BaseTest` passes `TEST_PORT` (8081), so a development server left running never collides with a suite run — a
collision that surfaced as every HTTP test class failing in configuration with "one of the listeners threw an
exception", which reads like a broken build rather than an occupied port.

Tests read `src/test/resources/config.properties` ahead of the user's file so they point at `agency_test`, exactly
as `latte-java/app` does. They do not override the `fusionauth.*` settings: the tests authenticate against the same
local provider the development server does.

## 7. Brief sources and polling

### 7.1 `GitService`

A thin `ProcessBuilder` wrapper. Every command uses `git -C <path>` rather than a working directory, redirects
stderr into stdout, and is bounded by a timeout; on timeout the process is destroyed forcibly and the call fails.

| Call                | Command                                    | Timeout |
|---------------------|--------------------------------------------|---------|
| `isWorkTree(path)`  | `git -C <path> rev-parse --is-inside-work-tree` | 10s |
| `pull(path)`        | `git -C <path> pull --ff-only`             | 60s     |
| `head(path)`        | `git -C <path> rev-parse HEAD`             | 10s     |

`--ff-only` is deliberate: the Agency must never create a merge commit in a developer's repository. A source that
has diverged fails the pull, logs, and keeps serving from the current `HEAD`.

### 7.2 `PollerService`

A daemon thread — `PollerService extends IntervalThread` — that runs one full cycle every `poller.intervalSeconds`
and wakes early on a `nudge()`. This mirrors the Handler's `ReceiveThread` and `DistributeThread`, down to sharing
a copy of `IntervalThread` itself: the interval is measured from the end of one run to the start of the next so a
slow cycle never queues runs back to back, the loop wraps `execute()` in `try/catch (Throwable)` so an escape
cannot silently stop the service for the life of the process, and shutdown is a flag plus a signal rather than an
interrupt so an in-flight build is never torn apart between the build and the insert.

Sources are polled one after another on that single thread, which is what makes the service lock-free. With no
concurrent cycle and no second caller, one Organization cannot build twice at once, so there is no
per-Organization lock and nothing to evict. The admin UI's "Rebuild now" button `nudge()`s and redirects; it never
builds on the request thread, which is the only thing that ever required the lock. The nudge carries no payload,
so a rebuild request runs the ordinary full cycle rather than a second, subtly different path for one
Organization — and the scheduled cycle already pulls every source, so this costs nothing a timer tick did not.

`poller.enabled` (default `true`) controls only whether the thread starts. The service is constructed either way,
so a nudge is always safe to send.

### 7.3 The cycle, per source

```
if !git.isWorkTree(path)          → status NOT_A_REPOSITORY, record, done
pullError ← git.pull(path) failed ? its output : null      (never aborts the cycle)
head      ← git.head(path)        → on failure: status NOT_A_REPOSITORY, record, done

if head == lastBuiltCommit && the Organization has ≥ 1 Brief version
                                  → status UNCHANGED, record lastPolledInstant, done

content ← builder.build(path)     → on failure: status BUILD_FAILED, record the message, done
                                     lastBuiltCommit is deliberately NOT advanced, so the next
                                     cycle retries and a fixed repository recovers on its own

if content.checksum == latest version's checksum
                                  → no insert; advance lastBuiltCommit; status UNCHANGED
else                              → insert version max+1 with source_commit = head (§9.3);
                                     advance lastBuiltCommit; status OK
```

Every outcome updates `last_polled_instant`, `last_status`, `last_error` and `last_pull_error`, so the admin UI
always shows why a source is in the state it is in. A failure on one source never affects another.

`pullError` is recorded separately from `last_error` because a cycle can legitimately have both a failed pull and a
successful build — the common case being a local test repository with no remote configured at all.

## 8. The Brief builder

`BriefBuilder.build(Path sourceRoot)` returns an unpublished `Brief` — one with a null `checksum` and a null
`version`, because neither is known until it is stored — or throws `BriefBuildException` naming the offending file. It touches no database and no network. A build is all-or-nothing: a Brief is never inserted partially.

### 8.1 Layout marker

`the-agency-hq-settings.json` must exist at the source root and carry a SemVer `version` compatible with
`BriefBuilder.SUPPORTED_LAYOUT_VERSION` (`1.0.0`). A missing or unparseable file fails the build. This is
deliberate: it is the only thing distinguishing a Brief source repository from an arbitrary directory someone typed
into the admin form, and pointing the Agency at the wrong repository should fail loudly at registration rather than
quietly publish a Brief full of application source code.

`SourceSettings.version` is an `org.lattejava.version.Version`, not a `String`, carried on the wire by
`@JSONField(asString = true)` — the codec converts through `Version`'s own `String` constructor on the way in and
`toString()` on the way out. Parsing therefore happens once, inside `SourceSettingsJSON.fromJSON`, and a malformed
version arrives as a parse failure naming the offending value rather than as a second validation step downstream.
`BriefBuilder` holds no version-parsing code at all; it checks compatibility with `Version.isCompatibleWith` and
nothing else.

The library owns the whole grammar, so degenerate inputs (`.`, `..`, a leading or trailing delimiter, two
delimiters in a row) are rejected without a guard per shape, and it is stricter than the hand-rolled `split('.')`
plus `parseInt` it replaced: `1.0.0.0` used to pass, because only the first dotted segment was ever examined. The
one malformed shape the codec cannot catch is an absent `version` member, which yields null because there is no
string to convert; `verifySettings` rejects that explicitly.

### 8.2 Walking the tree

Depth-first from the source root, considering only the five mapped top-level directories (§8.4). Anything else at
the root — `README.md`, `.gitignore`, `LICENSE` — is ignored and logged at DEBUG.

- `.git/` is never entered.
- **Symbolic links are never followed and fail the build** (decision 12). A link is the one construct that can make
  a path that validates as relative resolve outside the tree.
- Empty directories produce nothing; a Brief is a list of files, and the Handler creates ancestors itself.

### 8.3 Mission Type resolution

For a source file at relative path `p`, in order, first match wins:

1. A sibling file named `<p>.mission-types` — its lines are the Mission Types.
2. Otherwise, walking up from `p`'s directory to the source root, the first directory containing a `.mission-types`
   file supplies them. A nearer file overrides a further one.
3. Otherwise the empty list, which per `idea.md` means "applies to every Mission Type".

Lines are trimmed, blanks dropped, duplicates removed, order preserved, original case kept. Both sides lowercase
before comparing, so matching is case-insensitive by construction.

`.mission-types` and `*.mission-types` files are consumed as metadata and never emitted into a Brief. Neither is
`the-agency-hq-settings.json`.

The truth table in `idea.md` §Mission Types is the acceptance test for the Handler's filtering, not the Agency's —
the Agency only attaches the types. It is reproduced in the Agency's tests anyway, as a guard that the types it
attaches are the ones the table assumes.

### 8.4 Path mapping

Driven by a table, so adding an agent type is one entry and no code:

```java
record AgentType(String name, String outputRoot) {}
static final List<AgentType> AGENT_TYPES = List.of(
    new AgentType("claude", ".claude"),
    new AgentType("codex",  ".codex"));
static final List<String> SHARED_DIRECTORIES = List.of("agents", "rules", "skills");
```

| Source                | Output                                                    |
|-----------------------|-----------------------------------------------------------|
| `skills/**`           | `.claude/skills/**` **and** `.codex/skills/**`             |
| `rules/**`            | `.claude/rules/**` **and** `.codex/rules/**`               |
| `agents/**`           | `.claude/agents/**` **and** `.codex/agents/**`             |
| `claude/**`           | `.claude/**` (verbatim escape hatch)                       |
| `codex/**`            | `.codex/**` (verbatim escape hatch)                        |
| anything else at root | ignored                                                    |

A file under a shared directory therefore produces one Brief file per agent type. The entries differ only in
`path`; `content`, `checksum`, `mode` and `missionTypes` are identical, and the content string is shared in memory.

`agents/` has no output location in `idea.md`'s result tree — the source tree lists it but the result tree omits it.
Mapping it alongside `skills/` and `rules/` is the consistent reading and matches Claude Code's real
`.claude/agents/` directory.

### 8.5 Building each file

- **`content` and `encoding`** — the file's bytes are decoded as UTF-8 with a `CharsetDecoder` set to
  `CodingErrorAction.REPORT`. Success gives `encoding = "text"` and the decoded string; failure gives
  `encoding = "base64"` and `Base64.getEncoder().encodeToString(bytes)`. Strict decoding is the point: a lenient
  decoder replaces invalid bytes with U+FFFD, which would silently corrupt every binary asset.
- **`checksum`** — SHA-256 of the file's bytes, hex-encoded lowercase. Because the decoded bytes always equal the
  source bytes under both encodings, this is simply the checksum of the file on disk, which is exactly what the
  Handler recomputes.
- **`mode`** — `r-x------` if the source file is owner-executable, otherwise `r--------`. The executable bit
  matters: `idea.md`'s skill layout has a `scripts/` directory, and a script delivered `r--------` cannot run.
- **`missionTypes`** — from §8.3.

The builder hands its files over in whatever order the walk produced them. `Brief`'s constructor sorts them by
path (§9.1), so a build is deterministic regardless of filesystem iteration order without the builder having to
remember to sort.

### 8.6 Output path validation

Every output path is validated, and any violation fails the whole build. The rules mirror the Handler's planner
(`../handler` §8.3) exactly, because a Brief that violates them is not merely wrong — the Handler rejects the entire
plan, so one bad file silently stops that Organization updating on every machine. Failing at build time turns a
fleet-wide silent stall into one visible error next to the file that caused it.

A path is rejected when it is empty; contains a character below `0x20` or equal to `0x7F`; is absolute; has any
segment equal to `.` or `..`; has any segment which lowercased equals `.git`, `.handler-manifest` or `.gitignore`;
has any segment which lowercased contains `.handler-tmp-`; or collides with another output path in the same Brief.

The `.gitignore` rule mirrors `BriefPlanner`'s check in `../handler` one for one, including its case-insensitivity
and its application at every depth. It exists because the Handler creates and maintains `.gitignore` itself as part
of its own bootstrap, so a Brief that names one collides with a file the Handler just wrote. Unlike the rules above
it, this one rejects a path that looks entirely ordinary — `claude/.gitignore` or `skills/my-skill/.gitignore` is a
file an author would plausibly write on purpose — which is precisely what makes omitting it dangerous.

The `.git` rule is not theoretical. The escape hatches are verbatim, so a source file at `claude/.git/config`
becomes `.claude/.git/config`. The Handler rejects it — but only after the Agency has published it and every
Handler has downloaded it.

That timing is what gives every rule in this section its weight, and it is worst for `.gitignore`. The Handler
downloads the Brief, verifies every per-file checksum, commits it to its store, and therefore reports the new
version as current — and only *then* does `BriefPlanner.plan` throw and fail the entire Location. Files never
change, the Agency's UI shows `OK`, and the Handler reports itself up to date: a silent fleet-wide stall with no
signal anywhere. Failing the build here is the only place the failure is attributable to the file that caused it.

## 9. Versioning and checksums

### 9.1 Wire records

```java
@JSON public record BriefFile(String path, String encoding, String mode, String content,
                              String checksum, List<String> missionTypes) {}
@JSON public record Organization(UUID id, String name, Instant insertInstant, Instant updateInstant) {}
@JSON public record Brief(String checksum, Organization organization, Integer version,
                          List<BriefFile> files) {}
```

Member declaration order is the wire key order, and it matches the Handler's fixtures.

### 9.2 The Brief checksum

```
checksum = SHA-256( BriefJSON.toJSONBytes(unpublishedBrief) ), hex lowercase
```

`Brief.checksum` and `Brief.version` are both nullable, and `@JSON` omits null members, so a Brief straight out of
`build` serializes to exactly `organization` and `files`. The checksum is therefore purely content-addressed
without a second record existing to model the same thing minus two members: the same Brief content always produces
the same checksum regardless of how many versions came before it, which is what allows §9.3's comparison to be a
single equality test.

Both members are nullable rather than defaulted for that reason. A blank-but-present `checksum` would put the
member inside its own input, and a `0` version would make an identical rebuild hash differently once the version
moved on, so the compact constructor normalizes a blank checksum to null rather than to `""`.

Determinism rests on two properties, and both get a test: Latte JSON's generated serializer writes members in
declaration order, and `Brief` canonicalizes itself by sorting `files` by path in its constructor.

Everything reachable from a `Brief` is canonicalized by the record that holds it, because everything reachable
from a Brief feeds the checksum:

| Member | Canonical form | Why it would otherwise republish |
|--------|----------------|----------------------------------|
| `Brief.files` | sorted by path | filesystem iteration order, or any construction that skipped the builder |
| `BriefFile.missionTypes` | trimmed, lowercased, deduplicated, sorted | reordering or recasing a `.mission-types` file, which the Handler cannot observe — it lowercases both sides before comparing |
| `BriefFile.encoding`, `BriefFile.checksum` | lowercased, trimmed | already were |
| `Organization.name` | lowercased, trimmed | already was |
| `Organization.insertInstant`, `updateInstant` | truncated to milliseconds | both columns are `BIGINT` epoch millis, so an in-memory `Instant.now()` carries sub-millisecond digits the database would drop — a Brief built against a fresh Organization would hash differently from one built against the same Organization read back |

`BriefFile.content` is deliberately not canonicalized: it is the file's bytes, and normalizing anything about it
(line endings, trailing whitespace) would corrupt what the Handler writes to disk.

Canonicalization belongs to the record, not to the builder. The checksum hashes a `Brief`, and a Brief is
constructed in three places — built from a working tree, copied to attach a version at insert time, and parsed
back off the wire. Only the first went through the builder, so a sort there left the other two able to produce a
Brief whose checksum disagreed with the builder's for identical content. Paths are unique within a Brief (§8.6
rejects a tree that maps two sources onto one output path), so sorting by path is a total order.

### 9.3 Version assignment

Versions are per Organization, start at `1`, and are assigned as `MAX(version) + 1`. Rows are inserted and never
updated or deleted, so the history is a complete audit trail and a future rollback is a matter of choosing an older
row.

Before inserting, the new content checksum is compared with the latest version's. If they are equal, nothing is
inserted (decision 9) — the source's `last_built_commit` still advances, so the work is not repeated next cycle.

`insertBrief(Brief)` takes the Brief and returns it with the version filled in. Nothing is passed alongside it:
the owning Organization, the checksum, the commit it was built from, and when it was built are all already on the
Brief. The version and the row id are the method's to assign — the version because only the database can serialize
the race for it, the id because nothing outside `DatabaseService` refers to a Brief by it.

`Brief.sourceCommit` **is** the poller's `head`. One `git rev-parse` result becomes three things in a successful
cycle: the Brief's `sourceCommit`, the `briefs.source_commit` column, and the source's `last_built_commit`. They
were three parameters threaded through three signatures before the Brief carried it.

The document is written *without* `version`, `sourceCommit` or `insertInstant`. All three are columns, and
`toBrief` puts them back on the way out, so each is stored in exactly one place. Leaving the version out is what
lets the database assign it: a document carrying the number would have to be serialized before the `INSERT` that
decides it, which is exactly the ordering that forced the old two-statement shape.

The version is assigned by a scalar sub-select inside the `INSERT`, with `RETURNING version` handing the assigned
number back:

```sql
INSERT INTO briefs (id, organization_id, version, ...)
VALUES (?, ?, (SELECT COALESCE(MAX(version), 0) + 1 FROM briefs WHERE organization_id = ?), ...)
RETURNING version
```

The previous shape read `MAX(version)` in one statement and inserted in another, holding a stale maximum across a
full application round trip. The sub-select narrows that window to the statement rather than closing it: under
`READ COMMITTED` two concurrent statements can still evaluate it against the same snapshot and produce the same
number. `UNIQUE (organization_id, version)` remains the thing that makes a duplicate impossible, and the loser
still fails and retries on its next cycle. Genuinely serializing the assignment needs a per-Organization advisory
lock or `SERIALIZABLE` isolation, neither of which is worth it while a single poller thread is the only writer.

## 10. The Briefing API

### 10.1 Authentication

**Superseded 2026-08-06.** `Authorization: Bearer <token>` is still required, but the token is now a FusionAuth
OAuth access token verified against the provider's JWKS by Latte Web's API-mode OIDC middleware, installed on the
`/api` prefix. A missing header, a header that is not `Bearer <token>`, or a token that fails verification returns
`401` with no body, as before. See `docs/design/2026-08-06-oauth-authentication-design.md`.

The token is never logged, at any level.

<details>
<summary>The retired scheme, for the record</summary>

The token was compared against the set configured in `handler.tokens` with `MessageDigest.isEqual` over UTF-8
bytes, iterating the whole set rather than short-circuiting on the first match, so neither the comparison nor the
loop leaked timing information.
</details>

### 10.2 The decision

`BriefingService` decides from the asserted versions and what the database holds, reading the latter itself. Let:

- `entitled` — every Organization (decision 3)
- `deliverable` — those `entitled` Organizations with at least one Brief version
- `asserted` — the Organization ids in the request
- `stale` — the `deliverable` Organizations where the request has no entry, or the version differs, or the
  checksum differs

```
if deliverable is not empty && stale is empty && asserted == ids(deliverable)  → 304, no body
else                                                                            → 200
                                                                                  organizationIds = ids(entitled)
                                                                                  briefs          = the stale Briefs
```

Four parts of that condition are load-bearing:

- **`deliverable` must be non-empty to answer `304` at all.** Without this guard the formula answers `304` to a cold Handler polling an Agency where nothing has been built yet — `stale` is trivially empty and `asserted` (empty) trivially equals `ids(deliverable)` (also empty). That Handler would never learn which Organizations it is entitled to, because `304` is the steady state and nothing would ever dislodge it. The guard costs a `200` with an empty `briefs` array on every poll of an Agency with nothing built, which lasts only until the first Brief is built. This was found during implementation, when the rule as originally written failed its own acceptance test.

- **`organizationIds` is `entitled`, not `deliverable`.** An Organization registered but not yet built is still one
  the Handler is entitled to. Including it means the Handler logs "no Brief for this Organization" and skips the
  Location, rather than treating it as revoked and tearing the Location down.
- **`asserted == ids(deliverable)`, not `ids(entitled)`.** The Handler only asserts Organizations it actually holds
  Briefs for. Comparing against `entitled` would mean a single registered-but-unbuilt Organization made a `304`
  impossible forever, and every Handler on every machine would re-download every Brief on every cycle.
- **The set comparison at all.** Without it, an Organization deleted from the Agency while nothing else changed
  would produce a `304`, and the Handler would keep serving a Brief it is no longer entitled to — indefinitely,
  because a `304` is by definition the steady state.

A request body that is absent or empty is treated as an empty `currentVersions` — a Handler with a cold store. A
duplicate `organizationId` in `currentVersions` returns `400`; picking a winner would silently serve one of two
contradictory assertions.

### 10.3 Response assembly

The envelope is `BriefingResponse`, serialized by its generated codec:

```
{"organizationIds":["<uuid>",...],"briefs":[<brief>,<brief>]}
```

`DatabaseService` parses each stored document into a `Brief` as it reads the row (§11), so nothing above it ever
handles JSON text — the controller builds a `BriefingResponse` and hands it to the generated codec. The Agency
writes no JSON by hand anywhere.

An earlier revision concatenated the stored documents verbatim instead, to avoid the parse-and-rebuild and to
guarantee the Handler stored exactly the bytes recorded at build time. Both reasons weakened once the records
became self-canonicalizing (§9.2): `toJSON(fromJSON(document))` is now stable by construction, so re-serializing
reproduces the stored bytes rather than merely resembling them. What remains is a real cost, recorded so it is
understood rather than rediscovered:

- **Every poll parses every deliverable Brief**, including on the `304` path where none of them are sent. The
  documents were already being loaded from the database on that path, so this is CPU rather than I/O, but it is
  proportional to total Brief size on the endpoint every Handler polls on an interval. If that becomes the
  bottleneck, the fix is to compare against the row's `version`/`checksum` columns — which are already indexed
  and already selected — and materialize a `Brief` only for the Organizations that turn out to be stale. The fix
  is *not* a reduced stand-in for `Brief`: a caller that has a Brief's version and checksum but not its files is
  one click away from wanting the files, and a type that cannot hold them makes that a schema change rather than
  a field access.

`DatabaseService` hands out whole `Brief` objects everywhere for that reason, including the admin UI's version
history (`listBriefs`), which parses an Organization's full history to render a table of scalars. That is a real
cost on a page nobody polls, accepted so there is one Brief type rather than a full one and a projection of it.
`latestBriefVersions()` remains the one exception, and it is not a stand-in type — it returns `Map<UUID, Integer>`
for a listing cell that renders a single number per Organization, where materializing every Organization's newest
document would be work proportional to the whole fleet.
- **A stored document is re-serialized through today's codec, not the one that wrote it.** Canonicalization makes
  that a no-op today. It stops being one if the wire shape changes, at which point an old version reads back
  normalized to the new shape.

### 10.4 What changes when entitlements arrive

Contained entirely within §10.1 and the definition of `entitled` in §10.2:

- ~~`BriefingController` resolves the token to a user instead of a boolean.~~ **Done 2026-08-06.** The OIDC
  middleware binds the verified JWT to the request, and `OIDC.jwt().subject()` is the FusionAuth user id.
- `entitled` becomes that user's Organization memberships instead of every Organization.
- `403` becomes reachable, for a valid token whose user belongs to no Organization.

`BriefingService` derives the entitled set from `listOrganizations()`. That call becomes the user's memberships;
nothing else in the decision changes.

## 11. Admin UI

Server-rendered JTE, ~~no authentication (decision 1)~~, bound to localhost. Trailing slashes on listing pages
only, per `web-conventions.md`.

> **Superseded 2026-08-07.** `/app/**` now sits behind a FusionAuth browser session, and the page chrome carries
> the signed-in user and a sign-out control. See `docs/design/2026-08-06-oauth-authentication-design.md`. The
> localhost bind survives the change, for the reason recorded below.

### 11.1 Styling

Tailwind CSS 4, wired the way `latte-java/app` wires it. `src/main/css/app.css` is the entry point; the `css`
target compiles it one-shot to `web/static/css/app.css` and `run` depends on it, so `latte run` always serves a
current stylesheet. The `tailwind` target runs the same compile in `--watch` for development alongside a running
server. Both install the CLI on demand, so a fresh clone needs nothing but `latte run`. The compiled stylesheet is
generated output and is not checked in.

The compile hangs off `run` rather than `build` deliberately: the stylesheet is only ever read by a running server
off the filesystem (`Web.baseDir` is `web/`, so it is never packaged into the JAR), and hanging it off `build`
made every `latte test` shell out to npx to produce output no test reads.

The design follows `../website`. The `@theme` palette — `accent` (the logo's cyan), `brand` (its blue), and
`navy` (its near-black surfaces) — is copied verbatim from `../website/assets/css/main.css`, as are the
`dossier-label` and `grid-backdrop` motifs, so the admin UI and the marketing site read as one product. **These
values are a copy, not a fork**: change them on the site and copy them here.

Light and dark both ship. The mechanism is the site's, class-based rather than `prefers-color-scheme` alone:
`@custom-variant dark (&:where(.dark, .dark *))`, a `.dark` class toggled on `<html>`, and a three-state
preference in `localStorage` under the key `theme` — explicit light, explicit dark, or absent to follow the OS. A
small script in `<head>` applies the stored preference *before* the stylesheet loads, so a dark-mode reload never
flashes white first. The two codebases must agree on that key and those three states.

**All page scripts are external files under `web/static/js/`, and must stay that way.** `Main` installs
`SecurityHeaders.defaults()`, whose CSP is `default-src 'self'` with no `'unsafe-inline'` — an inline `<script>`
is silently refused. The failure is quiet and misleading: the theme toggle ships all three of its icons hidden and
relies on script to unhide one, so a blocked script renders as a button that occupies space and draws nothing,
with the page stuck in whatever theme the OS prefers. The logo is inlined into a JTE component for a related
reason: its lettering is `fill="currentColor"`, which cannot inherit the page's text colour through an `<img>` and
renders black on the dark navy background.

Static assets are served with `cache-control: public, max-age=604800`. A stylesheet or script edit therefore needs
a hard reload to show up in a browser that has already loaded the page — worth knowing before chasing a change
that appears not to have taken. Fingerprinted filenames (what `app` does with `ThemeSwitcher-0.1.0.js`) are the
real fix and are not done here.

Because every element now carries utility classes, tests assert on content rather than on markup — matching a bare
tag like `<td>1</td>` would break on a styling change instead of a behaviour change.

"Bound to localhost" was the entire justification for having no authentication, so it is bound explicitly rather
than by default: `Main` starts the server with `new HTTPListenerConfiguration(InetAddress.getLoopbackAddress(),
PORT)`, not `Web.start(int)`, whose default listener binds every interface. Anyone who could reach this port could
otherwise register an Organization pointing at an arbitrary local directory, make the Agency run `git pull` inside
it, and read and download any file the resulting Brief contains.

**Amended 2026-08-07.** Authentication arrived, so that justification no longer applies — but the bind did not
change, because a second one took its place: there is no TLS listener, and session cookies are marked `Secure`
only on an https request. Off loopback, over plain http, every admin session would travel in the clear. The bind
widens when there is a TLS listener to widen it onto.

| Route                                                                | Purpose                                                     |
|----------------------------------------------------------------------|-------------------------------------------------------------|
| `GET  /`                                                             | `303` to `/app/organizations/`                              |
| `GET  /app/organizations/`                                           | Every Organization with its source status and latest version |
| `GET  /app/organizations/new`                                        | New-Organization form (name + source Path)                   |
| `POST /app/organizations/`                                           | Create, then `303` to the detail page                        |
| `GET  /app/organizations/{organizationId}`                           | Source Path, last commit, poll status, errors, version list  |
| `POST /app/organizations/{organizationId}/rebuild`                   | Nudge the poller, then `303` back                            |
| `GET  /app/organizations/{organizationId}/versions/{version}`        | The version's files: path, mode, encoding, size, Mission Types |
| `GET  /app/organizations/{organizationId}/versions/{version}/files/{index}` | One file's content                                    |

Files are addressed by their **index** into the version's path-sorted file list, not by their path. A Brief path
contains slashes and would need a catch-all route; an index is unambiguous and needs no path decoding. The index is
stable because §8.5 sorts the list.

Text files render escaped inside `<pre>`. Base64 files render their size and type with a download link on the same
route plus `?download=true`, which streams the decoded bytes with `Content-Disposition: attachment`.

Validation lives in `service.validation` and throws `ValidationException`, which the controller renders back into
the form:

- **Name** — required, matched against `^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$` after lowercasing, and unique
  case-insensitively. `idea.md` makes names first-come-first-serve like NPM, so the character set is restricted for
  the same reason theirs is.
- **Path** — required, absolute, an existing directory, a Git work tree per `GitService.isWorkTree`, containing a
  valid `the-agency-hq-settings.json`, and not already registered to another Organization.

View models are records in `model.view` — `OrganizationsView`, `OrganizationDetailView`, `BriefVersionView`,
`BriefFileView` — and hold only what a template renders.

## 12. Data model

Schema is plain SQL under `src/main/resources/db/<semver>.sql`, applied in SemVer order by Latte Database's
`Migrator` when `DatabaseService` is constructed at startup. Never edit an applied migration — the checksum check
fails the next start. Add a higher-versioned file instead.

Following `latte-java/app`'s conventions: epoch-millis timestamps are `BIGINT`, enums are `TEXT` with a `CHECK`
constraint, both mapped by jOOQ forced-type converters.

`0.1.0.sql`:

```sql
CREATE TABLE organizations (
  id              UUID PRIMARY KEY,
  name            TEXT   NOT NULL,
  insert_instant  BIGINT NOT NULL,
  update_instant  BIGINT NOT NULL
);
CREATE UNIQUE INDEX organizations_uk_name ON organizations (LOWER(name));

CREATE TABLE brief_sources (
  id                   UUID PRIMARY KEY,
  organization_id      UUID   NOT NULL UNIQUE REFERENCES organizations (id) ON DELETE CASCADE,
  path                 TEXT   NOT NULL UNIQUE,
  last_built_commit    TEXT,
  last_polled_instant  BIGINT,
  last_status          TEXT   CHECK (last_status IN
                          ('BUILD_FAILED', 'NOT_A_REPOSITORY', 'OK', 'UNCHANGED')),
  last_error           TEXT,
  last_pull_error      TEXT,
  insert_instant       BIGINT NOT NULL,
  update_instant       BIGINT NOT NULL
);

CREATE TABLE briefs (
  id               UUID   PRIMARY KEY,
  organization_id  UUID   NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
  version          INT    NOT NULL,
  checksum         TEXT   NOT NULL,
  document         TEXT   NOT NULL,
  source_commit    TEXT,
  insert_instant   BIGINT NOT NULL,
  UNIQUE (organization_id, version)
);
CREATE INDEX briefs_idx_organization_version ON briefs (organization_id, version DESC);
```

`document` is `TEXT`, not `JSONB`, and that is not an oversight. `JSONB` normalizes whitespace and reorders keys on
storage, so the document read back would not be the document written, and §9.3's history is meant to be an exact
record of what was published. `TEXT` preserves it.

`UNIQUE (organization_id, version)` is what makes concurrent version assignment safe: two builds racing to claim
`MAX(version) + 1` cannot both win, and the loser retries. §9.3's sub-select makes the race far less likely to be
reached; this constraint is what makes it harmless when it is.

## 13. Error handling and logging

`System.Logger` throughout, per `architecture.md`.

| Level   | Used for                                                                                          |
|---------|---------------------------------------------------------------------------------------------------|
| ERROR   | Build failures, invalid output paths, a source Path that is missing or not a repository, database failures |
| WARNING | `git pull` failures                                                                               |
| INFO    | Version transitions (`organization=x version=12→13`), the per-cycle poller summary, startup        |
| DEBUG   | Per-file build decisions, ignored root entries, poller thread start/stop, timings                  |

Bearer tokens and database credentials are never logged.

Error responses are produced at the point they occur rather than by a middleware. An earlier draft of this section
specified an `ExceptionHandler` middleware mapping `ValidationException` to `400` and a `MissingException` to `404`;
neither was built, because for this milestone's four failure shapes the indirection cost more than it saved:

- **`ValidationException`** is thrown by `service.validation` and caught inline by `OrganizationController.create`,
  which re-renders the form with the error list. It never becomes a `400`, because the admin UI's answer to invalid
  input is the form again with the reasons on it — a status code alone would lose the reasons.
- **Not found** is `res.setStatus(404)` at each of the handful of admin-UI lookups that can miss (unknown or
  malformed Organization id, version, or file index). There is no `MissingException` type; a controller that has
  just failed a lookup is already exactly where the decision belongs, and every one of those sites returns
  immediately afterwards.
- **The Briefing API** returns bodyless status codes throughout — `401` for a missing or unknown bearer token, `400`
  for two contradictory assertions about one Organization — set directly in `BriefingController`.
- **A malformed request body** is the one case that is still handled by the framework: `BodySupplier.of` throws
  `BadRequestException`, which Latte Web renders as a `400`.

The observable behavior is identical to what the middleware would have produced. Introduce the middleware when a
failure shape appears that more than one controller has to produce the same way; until then it would be a layer with
one implementation and no second caller.

## 14. Testing

TDD — tests first for every unit below. `WebTest` from `org.lattejava.web.test` drives the HTTP layer.

**Contract tests.** The Handler's frozen fixtures in `../handler/src/test/resources/agency/` are the shared contract.
The Agency copies them into `src/test/resources/agency/` and asserts its generated envelope matches their shape,
key order, and defaults. A divergence between the two copies is a visible, reviewable API break — this is the only
mechanical link between the two repositories, so it earns its keep.

**Unit tests**

| Unit               | Focus                                                                                                          |
|--------------------|----------------------------------------------------------------------------------------------------------------|
| `BriefBuilder`     | Path mapping for all five roots; shared directories emitting one file per agent type; Mission Type precedence (sibling over directory, nearer over further); base64 detection on real binary bytes; the executable bit becoming `r-x------`; every §8.6 rejection including `claude/.git/config`; symlink failure; missing or wrong-major settings file |
| Checksums          | Determinism across runs and across filesystem iteration order; a reordered source directory produces an identical checksum; any content change produces a different one |
| `GitService`       | Work-tree detection; `HEAD` on a real temporary repository; pull failure on a repository with no remote; timeout handling |
| `PollerService`    | Unchanged commit skipping the build; identical content skipping the insert; build failure not advancing the commit; a failed pull still building; a nudge waking the thread ahead of its interval and `shutdown()` stopping it |
| Validation         | Name pattern and case-insensitive uniqueness; Path absolute, existing, a work tree, unique                      |

**`BaseTest`** — every test that needs the server, the database, or the service singletons extends it, mirroring
`latte-java/app`. It starts one `Main` on `TEST_PORT` in `@BeforeSuite` (TestNG runs classes sequentially and only
one can bind the port), exposes `main`, `db` and `test`, and truncates the database in `@BeforeMethod` so each
test starts from empty. `DELETE FROM organizations` is the whole reset: `brief_sources` and `briefs` both cascade
from it.

That reset replaced per-class cleanup bookkeeping, and with it the suite's old leak detector — a `count(*) == 0`
assertion in `DatabaseServiceTest` that fired when some other class forgot to delete what it created. Isolation is
structural now rather than a convention every class has to remember.

Pure unit tests — path mapping, JSON shapes, SemVer, the Git wrapper, `BriefingService`'s decision matrix — do not
extend it, and should not: booting a server to test a regex is pure cost. `DatabaseServiceTest` also stays
standalone, because it tests `DatabaseService`'s *construction* (migration failure, pool cleanup) and so must build
its own instances.

`PipelineIntegrationTest` is the one class that opts out of the per-method reset, by overriding `beforeMethod()`
with an empty body. Its scenarios are a single narrative chained with `dependsOnMethods`, each building on the
state the last one left, so emptying the database between them would delete the subject of the next assertion. It
resets once in `@BeforeClass` instead.

The §10.2 decision matrix is covered by `BriefingAPITest` rather than by a unit test of `BriefingService`: cold
store, current, stale version, stale checksum, unbuilt Organization not blocking a `304`, unbuilt Organization
still listed in `organizationIds`, deleted Organization forcing a `200`, unparseable assertion, duplicate
assertion rejected. The service reads the database itself, so a test of it needs real rows anyway — at which point
driving the same scenario over HTTP costs nothing extra and asserts the response the Handler actually receives.

Every `200` from the Briefing API is asserted as one whole `BriefingResponse`:

```java
.assertBodyAs(json, b -> b.equalTo(BriefingResponse::fromJSON,
    briefingResponse(List.of(organization), List.of(storedBrief))))
```

The body is parsed by the same codec the Handler uses and compared by record equality, so the entitled set, the
delivered Briefs, their order, and every member of every Brief and its Organization all have to match at once.
`BaseTest.briefingResponse` builds the expected value in the canonical §10.2 order, which is what makes the
pairing of `organizationIds[i]` with `briefs[i]` an assertion rather than a convention.

Nothing is asserted piecewise, because piecewise assertions are silently blind to whole categories of defect —
most obviously an *extra* entry. A check on `/briefs/0/checksum` passes just as happily when the response carries
one Brief as when it carries five, so a change that stopped filtering out the up-to-date Briefs would ship
undetected. Substring matching was weaker still, and wrong in both directions: `contains("\"briefs\":[]")` passes on
a document whose braces do not balance, and fails on the same document pretty-printed.

Parsing is also the well-formedness check, which is why there is no separate "is this valid JSON" test. HTML
responses stay on `StringBodyAsserter`, as does the `304`, whose body is empty and therefore not JSON at all.

**Integration tests** — the whole pipeline against temporary Git repositories. Fixtures live under `build/test/` by
default; a test that needs a repository genuinely outside this project's own Git work tree creates it in the system
temp directory instead and says why at the point it does so (`GitServiceTest`, `PipelineIntegrationTest`).

- Register an Organization, commit a source tree, poll, and assert version 1 with the expected files and modes
- Commit a change and assert version 2; commit an unrelated `README` and assert **no** version 3
- A Handler-shaped request with no `currentVersions` receives every Brief
- The same request repeated receives `304`
- A corrupt checksum in `currentVersions` forces the Brief to be resent
- Deleting an Organization changes `organizationIds` and forces a `200` rather than a `304`
- An unknown or missing bearer token receives `401`
- A build failure leaves the previous version live and serving

Tests point at `agency_test` and never touch a developer's real database or home directory.

## 15. Build and tooling

`project.latte` gains, mirroring `latte-java/app`:

- `compile-processors`: `org.lattejava:json` (the `@JSON` processor)
- `compile`: `gg.jte:jte`, `gg.jte:jte-runtime`, `org.lattejava:database`, `org.jooq:jooq`, `com.zaxxer:HikariCP`,
  `org.postgresql:postgresql`, plus `io.r2dbc:r2dbc-spi` and `org.reactivestreams:reactive-streams`, which jOOQ's
  `module-info` declares `requires static` and which javac needs on the compile module path
- a build-only `codegen` group and a `codegen` target running `org.jooq.codegen.GenerationTool`
- `semanticVersions` mappings for `io.r2dbc:r2dbc-spi:1.0.0.RELEASE` and its parent POM, whose non-semantic
  versions Latte cannot otherwise resolve
- `database` and `codegen` targets via `org.lattejava.plugin:database`

`latte-java/app`'s `project.latte` documents each of these workarounds inline; those comments are worth carrying
over rather than rediscovering.

## 16. Conventions

`.claude/rules/` applies: SPDX copyright headers on every file including `module-info.java`; 2-space indent with
4-space continuations; uppercase acronyms (`toJSON()`, `theAgencyURL`, `SQLBuilder` — never `Json`, `Url`); members,
imports, `requires` and `exports` alphabetized; `[value]` brackets in every error message; 120-column target;
Conventional Commits on a feature branch, squash-merged.

From `architecture.md`: Java 25 throughout — module imports, `System.Logger`, `var`, switch expressions and pattern
matching over the sealed `BriefingOutcome`, unnamed variables, virtual threads. No reflection. No dependency beyond
the approved list.

Models normalize in their compact constructors, per `web-conventions.md`.
