# The Agency — Bitbucket Brief Sources

## 1. Purpose

Add Bitbucket Cloud as a third kind of Brief source, behaving exactly as GitHub and GitLab do wherever Bitbucket
allows it, and offered only when the server holds its credentials.

This extends `docs/design/2026-09-08-gitlab-brief-source-design.md` and changes none of its decisions: the host seam
(`RepositoryClient`, `SourceCatalog`), the one controller, the one config contract, and the state cookie are used as
they are. Everything downstream of the client — the Brief document, its checksum, the version history,
`POST /api/v1/briefing` — is unchanged.

## 2. Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Scope | Bitbucket Cloud only | Bitbucket Data Center is a different product with a different API (1.0, self-hosted). Every URL is therefore fixed and `BitbucketConfig` carries no instance origin. |
| Repository name | `workspace/repository`, Bitbucket's `full_name`, in one member `repository` | It is the string Bitbucket uses in its own URLs. A workspace slug cannot contain a slash, so the client splits at the first one for the API's two path segments. |
| Credential | Bitbucket OAuth consumer: key and secret as HTTP Basic on the token endpoint | That is how Bitbucket authenticates the consumer; GitHub and GitLab take them in the form. |
| Scopes | `account` and `repository` (read), declared on the consumer; no `scope` on the authorize request | Bitbucket does not accept a scope parameter on individual requests. |
| Redirect URI | Sent on both the authorize and the token request | Bitbucket accepts a `redirect_uri` that equals or extends the consumer's configured callback URL; the Agency's callback is exactly the configured one. |
| File modes | From the `src` directory listing, walked recursively with `max_depth`, whose file entries carry `attributes` | Bitbucket has no Git tree endpoint. `BitbucketTreeEntry.mode()` maps `link` → `120000`, `subrepository` → `160000`, `executable` → `100755`, else `100644`. |
| Repository listing | The account's workspaces (`GET /2.0/user/workspaces`), then each workspace's repositories (`GET /2.0/repositories/{workspace}`) | Bitbucket removed the cross-workspace `GET /2.0/repositories` and its `role` filter on April 14, 2026 (changelog CHANGE-2770); the removed endpoint answers HTTP 410. The per-workspace listing needs no permission the consumer does not already hold. |
| Listing completeness | `contents()` fails if the archive holds a file the listing does not | The listing is bounded by depth rather than truncated or paged whole. A missing entry would silently revert a mode to the default. |
| Archive | `https://bitbucket.org/{workspace}/{repository}/get/{commit}.zip` with the bearer token | Bitbucket's API has no archive endpoint; the website's download takes the same token. A redirect is followed without the `Authorization` header, and a non-ZIP answer (a sign-in page) is reported as such. |
| Ref resolution for a file | `readFile` resolves the ref to a commit first | `src/{ref}/{path}` cannot tell where a ref with a slash in it ends and the path begins, and answers 404. |
| Display order | `BriefSourceType` is GitHub, GitLab, Bitbucket | The Sources page lists kinds in declaration order. |

## 3. Bitbucket specifics

| Step | Endpoint | Notes |
|------|----------|-------|
| Authorize | `https://bitbucket.org/site/oauth2/authorize?client_id&redirect_uri&response_type=code&state` | No scope. |
| Token | `POST https://bitbucket.org/site/oauth2/access_token`, Basic `key:secret`, form `grant_type=authorization_code&code&redirect_uri` or `grant_type=refresh_token&refresh_token` | A rejected grant is a 400/401 with an `error` body, read as "no credential". Access tokens last two hours; refresh tokens never expire. |
| Account | `GET /2.0/user` | `username`, falling back to `nickname`, then `display_name`. Needs the `account` permission. |
| Workspaces | `GET /2.0/user/workspaces?pagelen=100&fields=next,values.workspace.slug` | Follows `next`, at most 10 pages. Every workspace the account belongs to. |
| List | `GET /2.0/repositories/{ws}?pagelen=100&fields=next,values.full_name,values.mainbranch.name` | Once per workspace. Follows `next`, at most 10 pages per workspace. Every repository in the workspace the account can read; the picker sorts the union. |
| Head | `GET /2.0/repositories/{ws}/{repo}/commits/{ref}?pagelen=1&fields=values.hash` | Documented to take a ref name or a SHA; a branch with a slash works. 403/404 is `null`, 401 is unauthorized. |
| File | `GET /2.0/repositories/{ws}/{repo}/src/{commit}/{path}` | Raw bytes. The ref is resolved to `{commit}` first. |
| Tree | `GET /2.0/repositories/{ws}/{repo}/src/{commit}/?max_depth=100&pagelen=100&q=type="commit_file"&fields=next,values.path,values.type,values.attributes` | Files only; at most 100 pages. |
| Archive | `GET https://bitbucket.org/{ws}/{repo}/get/{commit}.zip` | One root directory `{ws}-{repo}-{short sha}/`, so `Archives.unzip` serves it. |

## 4. Configuration

| Setting | What it is |
|---------|-----------|
| `bitbucket.clientId` / `bitbucket.clientSecret` | The OAuth consumer's key and secret. Setting both offers Bitbucket. |

## 5. Routes

Unchanged in shape: `{kind}` gains the value `bitbucket` (`GET /app/oauth/bitbucket/start`, `/callback`,
`GET`/`POST /app/organizations/{id}/sources/bitbucket`). Bitbucket has no install trip, so no install/setup routes.

## 6. Schema

`0.4.0.sql` widens `brief_sources_ck_type` to `('BITBUCKET', 'GITHUB', 'GITLAB')`. A Bitbucket row is
`type = 'BITBUCKET'`, `source = <workspace/repository>`, and
`source_config = {"type": "BITBUCKET", "connection": {...}, "repository": ..., "branch": ...}`.

## 7. Testing

`FakeRepositoryClient` implements `BitbucketClient` too; `FakeHosts` registers a third instance under the test profile.
`BitbucketConnectionIntegrationTest` runs the whole `RepositoryConnectionBaseTest` suite against Bitbucket and adds
the scope-less authorize request, the repository description and identity, and the absence of an install trip.
`SourceCatalogTest` covers availability, the authorize URL and the fresh source; `MigrationTest` applies `0.4.0` on
top of the migrated schema and reads a Bitbucket row back through the codec; `BriefSourceRepositoryTest` stores a
Bitbucket source beside a GitHub and a GitLab source of the same name.

The real client is written against the live public API's shapes (checked against `atlassian/aui`) and Atlassian's
OAuth documentation, but the suite fakes the host, so the listing's two levels are an HTTP detail no test exercises.
The first connection to a real consumer, on September 13, 2026, found the token exchange and the account read
behaving as documented and the cross-workspace listing gone (HTTP 410, CHANGE-2770), which is what put the two-level
listing above in its place. The archive download and the tree listing remain to be checked against a real
repository.
