# The Agency — GitLab Brief Sources

## 1. Purpose

Add GitLab as a second kind of Brief source, behaving exactly as GitHub does wherever GitLab allows it, and offer a
kind of source only when the server holds its credentials.

This supersedes parts of `docs/design/2026-08-08-github-brief-sources-design.md`: §10 (the routes are now one set
per kind), §11 (the GitHub credentials are no longer required configuration and no longer ship as `replace-me`),
and §12 (the fake is one class per host). Everything downstream of the client — the Brief document, its checksum,
the version history, `POST /api/v1/briefing` — is unchanged.

## 2. Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| One client contract | `RepositoryClient` (package `source`), implemented by `GitHubHTTPClient` and `GitLabHTTPClient` | The handshake, the picker, the validator, the poller and the link service were one host's worth of code each; a second copy would have been a thousand lines that drift. |
| One controller | `RepositorySourceController`, told the kind by the route table | Same routes, same cookie, same ownership checks, same picker. GitHub's install/setup pair stays on it as the one host-specific trip. |
| One config contract | `BriefSourceConfig` gains `connection()`, `fullName()`, `branch()`, `withConnection`, `withRepository`, and describes itself with `details()` and `url()` | Every kind there is (and the next, Bitbucket) is an OAuth-connected git repository. The JSON codec requires the sealed interface's permitted subtypes to be records, so there is no intermediate interface: the contract lives on the root and a kind that is not a repository would answer `null`. |
| Repository name | One string, `fullName`, as the host spells it | `owner/repository` on GitHub; `group/subgroup/project` on GitLab, where the namespace nests and the API takes the whole path URL-encoded. `GitHubConfig` keeps `owner` and `repository` because that is the stored document; `GitLabConfig` stores `project`. |
| Shared records | `OAuthTokens`, `OAuthTokenResponse`, `OAuthConnection`, `TreeEntry`, `RepositoryContents` move to `model` | GitLab's token endpoint and tree entries carry the same member names as GitHub's. The renames change no JSON member, so every stored `source_config` document reads back unchanged. |
| Availability | `SourceCatalog`: a kind is configured when its `clientId` and `clientSecret` are both set | A checked-in working credential would let anyone who cloned the repository act as it, so the default has to be "none". Hiding an unconfigured kind is more honest than a button that fails. `github.appName` is required once GitHub is configured (startup failure otherwise). |
| Unconfigured but connected | The poller records `FETCH_FAILED` ("not configured") and does not touch the credential; the Sources page warns | A refresh attempted without the application's secret is refused, and the refusal would be read as a revocation and strip a credential a restored configuration would have used. |
| Nothing configured | The Sources page says so and names the configuration keys | The Owner who lands there needs to know what to ask for. |
| Display | A source describes itself: `details()` returns the rows the Organization's page shows (Repository/Project with its `url()`, Branch); the page renders the kind's label and then those rows | The page must not assume what a source consists of. `GitLabConfig` stores the instance's `baseURL` at creation (`SourceCatalog.unregistered`) so it can build its own URL, and so a source stays on the instance it was authorized on if `gitlab.baseURL` later changes. |
| State cookie | `nonce:kind:organizationId`, one cookie for every trip | A return from one host cannot complete a trip started for another. |

## 3. GitLab specifics

| Step | Endpoint | Notes |
|------|----------|-------|
| Authorize | `{base}/oauth/authorize?client_id&redirect_uri&response_type=code&scope=read_api&state` | An OAuth application on the instance, confidential, `read_api` only. |
| Token | `POST {base}/oauth/token` | Same form as GitHub's plus `grant_type`. A rejected grant is a 400/401 with an error body, read as "no credential"; GitHub answers 200 for both. Refresh tokens are single-use and never expire; access tokens last two hours. |
| Account | `GET /api/v4/user` | `username`. |
| List | `GET /api/v4/projects?membership=true&simple=true&order_by=path&sort=asc&per_page=100` | Follows `Link: rel="next"`, at most 10 pages. No installation level: membership is the whole grant. |
| Head | `GET /api/v4/projects/{path}/repository/commits/{ref}` | Branch, tag or SHA, as GitHub's. 403/404 is `null`, 401 is unauthorized. |
| File | `GET /api/v4/projects/{path}/repository/files/{path}/raw?ref=` | The settings marker, at registration. |
| Tree | `GET /api/v4/projects/{path}/repository/tree?recursive=true&pagination=keyset&per_page=100&ref=` | Paginated where GitHub truncates; more than 100 pages fails the build. |
| Archive | `GET /api/v4/projects/{path}/repository/archive.zip?sha=` | Same layout as GitHub's zipball — one root directory — so one `Archives.unzip` serves both. A redirect is followed without the `Authorization` header. |

GitLab returns bare JSON arrays for lists. The codec parses documents whose root is an object, so the client wraps a
list body as `{"items": [...]}` and parses it into `GitLabProjects` or `GitLabTree`.

## 4. Configuration

| Setting | What it is |
|---------|-----------|
| `gitlab.clientId` / `gitlab.clientSecret` | The GitLab OAuth application's credentials. Setting both offers GitLab. |
| `gitlab.baseURL` | The instance's origin. Defaults to `https://gitlab.com`. |
| `github.clientId` / `github.clientSecret` | Unchanged in meaning; no longer required and no longer defaulted. Setting both offers GitHub. |
| `github.appName` | Required when GitHub is configured. |

## 5. Routes

| Route | Purpose |
|-------|---------|
| `GET /app/oauth/{kind}/start` | Begin the authorization with that host |
| `GET /app/oauth/{kind}/callback` | Complete it; return to the Sources page with `?status=&type=` |
| `GET /app/oauth/github/install`, `/setup` | GitHub's installation trip, unchanged |
| `GET /app/organizations/{id}/sources` | One card per configured kind |
| `GET`/`POST /app/organizations/{id}/sources/{kind}` | The picker for that kind |

`{kind}` is `github` or `gitlab` (`BriefSourceType.slug()`). Every kind's routes exist on every server; the
controller answers 404 for a kind that is not configured.

## 6. Schema

`0.4.0.sql` widens `brief_sources_ck_type` to `('GITHUB', 'GITLAB')`. A GitLab row is `type = 'GITLAB'`,
`source = <path with namespace>`, and `source_config = {"type": "GITLAB", "connection": {...}, "baseURL": ..., "project": ..., "branch": ...}`.

## 7. Testing

`FakeRepositoryClient` implements both marker interfaces; `BaseTest` holds one instance per host and hands both to
`Main`. `RepositoryConnectionTestBase` runs the whole handshake, credential, picker and first-build suite once per
kind through `GitHubConnectionIntegrationTest` and `GitLabConnectionIntegrationTest`; the GitHub subclass adds the
install trip, the GitLab subclass adds nested group paths, the `read_api` scope, and the absence of an install
trip. `SourceCatalogTest` covers availability and URL building from configuration files; `PollerServiceTest`
covers a source whose kind is unconfigured; `MigrationTest` applies `0.4.0` on top of the migrated `0.3.0` schema.
The test configuration carries credentials for both kinds, so the suite exercises the configured state; the
unconfigured state is covered at the catalog.
