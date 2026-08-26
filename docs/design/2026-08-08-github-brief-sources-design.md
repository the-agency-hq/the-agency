# The Agency — GitHub Brief Sources

## 1. Purpose

Move Brief sources off the Agency's own filesystem and onto GitHub. An Organization is pointed at a GitHub
repository through the admin UI, authorized by the operator's own GitHub account, and polled through GitHub's REST
API instead of `git pull` against a local work tree.

This supersedes the local-work-tree half of `docs/design/2026-07-30-brief-pipeline-design.md` — §7 (the Brief
source), the `path` column of §9, and the "source path" field of §11. Everything downstream of the builder is
unchanged: the Brief document, its checksum, the version history, and `POST /api/v1/briefing` are all exactly as
they were, and every Brief published before this change stays valid and keeps serving.

**Why.** The previous design required the Agency to run on the same machine as a clone of every source repository,
kept current by someone. That is a deployment model with exactly one deployment: a developer's laptop. Nothing
about it survives contact with a hosted Agency, where there is no laptop, no clone, and no one to run `git pull`.

**What is deliberately not here.** There is no local-filesystem source any more, not even as an option. An on-prem
deployment that wants one gets a second `GitHubClient`-shaped implementation when there is an on-prem deployment
to want it; carrying a second, untested source kind in the meantime costs more than rewriting it later.

## 2. The workflow

1. The operator signs in to the admin UI (unchanged — FusionAuth, `docs/design/2026-08-06-oauth-authentication-design.md`).
2. **New Organization**: they type a display name. The Organization exists from this moment, with no source, and
   the browser lands on its view page.
3. The view page warns that the Organization is not connected to GitHub, with the button that starts the OAuth
   authorization against the Agency's GitHub App. The same warning comes back whenever the connection dies — a
   revoked or lapsed authorization is the same state as never having had one, fixed by the same button.
4. GitHub returns to `/app/oauth/github/callback`. The Agency exchanges the code for a user-to-server credential,
   stores it in columns on the Organization's own `organizations` row, and returns the browser to the view page.
5. **Connect a repository**: a picker listing every repository the Organization's credential can reach. They pick
   one and a branch. Unreachable while the Organization is unconnected — it redirects back to the view page, which
   is where the connection is offered.
6. The Agency verifies the repository is a Brief source, registers it, and nudges the poller.

The view page is the anchor of the whole sequence: creating lands there, the callback always returns there, and
the connection warning lives there. Connecting GitHub and picking a repository are separate pages because each
needs what came before it — the Organization has to exist for the callback to have somewhere to return to, and the
picker cannot render until the authorization it lists repositories with has been granted.

An Organization with no source is an ordinary state, not a broken one — it is where every Organization sits between
steps 2 and 6. The poller has nothing to poll for it, and the admin UI says so and offers the link to finish.

## 3. Why a GitHub App, and whose credential

A **GitHub App**, not an OAuth App. The difference that matters is per-repository consent: an App is installed on
an account over the repositories that account chooses to give it, so authorizing the Agency grants it a Brief source
repository and nothing else. An OAuth App's `repo` scope is all-or-nothing across every repository the user can
reach, which is not a reasonable thing to ask for in order to read a directory of Markdown.

The credential is the **operator's own user-to-server token**, not an installation token. This follows from the
workflow above — the repository picker has to show what *this* operator can see. Completing the handshake grants
that authorization **to the Organization**: the credential is stored against the Organization being connected, not
against the operator, so each Organization holds exactly one GitHub authorization and stands or falls on its own.

The consequence is stated plainly rather than hidden: when an Organization's authorization lapses — revoked,
expired past refresh, or the installation removed — its source goes `NOT_CONNECTED` and stays there until a human
reconnects it. That is correct. Nobody is entitled to a private repository because a background job used to be.
The published Brief keeps serving throughout; a lapsed authorization says nothing about whether the content it
produced is still right.

## 4. Where the credential lives

In five nullable columns on the `organizations` row: `github_login`, `github_access_token`,
`github_access_expiration`, `github_refresh_token`, and `github_refresh_expiration`. `github_access_token IS NOT
NULL` is what "connected" means; `github_login` is display text for the connect page. The expirations are epoch
millis, like every other instant column.

Storing the credential beside the thing it authorizes gives it exactly the right lifetime: deleting an
Organization takes its GitHub credential with it, with nothing to remember to clean up. Keying it by Organization
rather than by operator also keeps Organizations independent — reconnecting one never touches another's
credential, even when the same GitHub account authorized both.

An earlier revision of this design stored the credential in FusionAuth, as an `IdentityProviderLink` against the
operator's identity, precisely so the Agency's database held no bearer credential. That bought real problems: the
credential was shared by every Organization the operator connected (one revocation took them all down), FusionAuth
1.67.1 silently discards the link API's `data` map so the whole credential had to be smuggled through the `token`
member as a JSON document, and the link API has no update so every eight-hour refresh was a delete-then-create with
a failure window. The columns make all of that ordinary: typed values, a single-row `UPDATE`, and per-Organization
scope. The cost, stated honestly, is that a dump of the Agency's database now contains live GitHub tokens; access
to the database has to be treated accordingly.

In the model, the connection is not a separate type to fetch on its own: `Organization` carries the whole row,
with the credential nested as `model.GitHubConnection`, so reading an Organization reads its connection in the
same single `SELECT`.

Credential writes bump `update_instant` like every other write to the row — which means it moves on every
eight-hour token refresh. That is safe for Brief versioning because the Brief document embeds only the
Organization's **identity**: `BriefBuilder` nulls the connection and the instants before the Brief is checksummed
— the connection because it is a live bearer credential and the document is served to every Handler, the instants
because they would poison the checksum — so the checksum depends on the id, the name, and the files, and a token
refresh can never publish a new version of an identical Brief.

## 5. Polling

One GitHub request per unchanged source per cycle:

| Step | Endpoint | When |
|------|----------|------|
| Resolve the branch | `GET /repos/{owner}/{repo}/commits/{ref}` (`Accept: application/vnd.github.sha`) | Every cycle |
| Read the file modes | `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` | Only when the head moved |
| Download the content | `GET /repos/{owner}/{repo}/zipball/{sha}` | Only when the head moved |

That ratio is what makes polling every Organization on a one-minute timer affordable against a rate limit measured
in thousands of requests an hour. A cycle that downloaded first and compared afterwards would cost a full archive
per Organization per minute forever.

Both fetches use the commit SHA the first step resolved, never the branch name, so the two halves of a download
describe one state of the repository even if somebody pushes between them.

**Why the tree as well as the archive.** A Brief carries each file's mode, because the Handler writes these files
out and an executable script has to stay executable. GitHub's zipball records Unix permissions in each entry's
external attributes and `java.util.zip` exposes no way to read them, so the modes have to come from somewhere else.
The recursive tree is that somewhere, at the cost of one extra request per *build*. It also carries the two things
the archive cannot distinguish: a symbolic link, which the archive stores as an ordinary small file whose content is
the path it points at, and a submodule, which it omits entirely.

A truncated tree fails the build rather than being used. GitHub caps a recursive tree and then returns a prefix of
it silently; a build that ignored the flag would publish a Brief whose files had quietly reverted to the default
mode.

## 6. The builder no longer touches a filesystem

`BriefBuilder` takes a `github.RepositoryContents` — the whole repository at one commit, as a map of paths to bytes
and a map of paths to Git modes — instead of a `Path`. It is now pure in the strong sense: no database, no network,
*and* no filesystem.

In memory rather than unpacked to a temporary directory, and deliberately. A Brief source tree is prose and
configuration, so the whole of one is smaller than the JSON document the Agency already builds out of it and holds
in a single string. Writing it to disk first would buy nothing and cost the two things a filesystem always costs: a
temporary directory to clean up on every failure path, and a build whose outcome depends on the umask, the
case-sensitivity, and the free space of whatever machine happens to be running it. `MissionTypeResolver` moved the
same way, from `java.nio.file.Path` to `/`-separated strings, so the same repository resolves the same Mission Types
everywhere.

A hard ceiling (`GitHubHTTPClient.MAX_CONTENT_BYTES`, 64 MB) bounds the decompression, because an unbounded one is
an out-of-memory failure that takes the whole Agency down rather than one Organization's build.

Symbolic links remain a hard build failure, as they were, and for the same reason: the Handler writes these files
out, and a link is the one construct that turns a valid relative path into something resolving outside the tree.
What changed is only how one is detected — the tree's `120000` mode rather than `Files.isSymbolicLink`.

## 7. Validation at registration, not at first poll

`SourceValidator` fetches and **parses** `the-agency-hq-settings.json` at the ref being registered, using
`BriefBuilder`'s own verification rather than a weaker copy of it. Two requests, one of them for a file a few dozen
bytes long.

Without it, a repository the Agency cannot build registers cleanly and then fails on every cycle from then on, with
the only evidence a `BUILD_FAILED` status on a detail page nobody has a reason to open yet — arbitrarily long after
the form submission that caused it. Answering the question late is what makes it expensive.

## 8. Statuses

`SourceStatus` gains `FETCH_FAILED` and `NOT_CONNECTED` and loses `NOT_A_REPOSITORY`. The three failures are kept
apart because each is a different person's problem, and the admin UI colours them accordingly:

| Status | Whose problem | Clears itself? |
|--------|---------------|----------------|
| `BUILD_FAILED` | Whoever wrote the last commit to the source repository | On the next good commit |
| `FETCH_FAILED` | Usually nobody — GitHub had a bad minute, or the branch is gone | Usually, on the next cycle |
| `NOT_CONNECTED` | The Agency operator: the authorization has lapsed | Never, until someone reconnects |

This is why `GitHubUnauthorizedException` exists as a distinct type. GitHub answers `401` for a rejected credential
and `403`/`404` for a repository a perfectly good token simply cannot see — deliberately `404`, so a private
repository cannot be probed for existence. Collapsing the two would leave a source retrying forever against a
credential that will never work, under a status that tells the operator to wait.

A build failure never advances `last_built_commit`, so a fixed repository recovers on the next cycle without anyone
noticing it had broken.

## 9. Schema

Nothing had been released when this design landed, so there is no migration: the GitHub shape ships in the initial
schema, `db/0.1.0.sql`. `organizations` carries the five `github_*` credential columns (§4), and `brief_sources`
names a repository — `owner`, `repository`, `branch` — where the superseded design had a filesystem `path`. The
old columns and statuses never shipped anywhere they would need migrating from.

`briefs` is untouched. A stored Brief is content plus provenance and neither depended on where the content came
from.

`brief_sources_uk_repository` is unique on `(LOWER(owner), LOWER(repository))` — one repository serves one
Organization, which is what `path TEXT NOT NULL UNIQUE` used to say about a work tree. Case-insensitive because
GitHub is: `Acme/Briefs` and `acme/briefs` are one repository, and a unique index over the raw text would happily
register both. Both sides are lowercased *by Postgres* rather than in Java, for the same reason
`findOrganizationByName` is: two different case-folding implementations either side of a comparison is how a check
reports a repository free that the index then rejects.

## 10. Routes

| Route | Purpose |
|-------|---------|
| `GET /app/organizations/new` | The name form |
| `POST /app/organizations/` | Create, then redirect to the Organization's view page |
| `GET /app/organizations/{id}` | The view page: connection warning, source, and version history |
| `GET /app/organizations/{id}/connect` | The repository picker; redirects to the view page while unconnected |
| `POST /app/organizations/{id}/connect` | Register the picked repository |
| `GET /app/oauth/github/start` | Begin the GitHub authorization |
| `GET /app/oauth/github/callback` | Complete it, then return to the Organization's view page |
| `GET /app/oauth/github/install` | Send the operator to GitHub to install the App on an account, or change what an installation covers |
| `GET /app/oauth/github/setup` | The App's **setup URL**: where GitHub returns the browser after an install or update. Returns to the picker |

All four routes sit **inside** the gated `/app` prefix. Granting an Organization a GitHub credential is an operator
action, so an unauthenticated visitor must never be able to start a connection or land a callback that stores one.
The browser profile's cookies are `SameSite=Lax`, so the session rides along on a top-level navigation arriving from
github.com.

The install pair (added 2026-08-25) exists because the picker lists what the credential can see *now*, and the
operator changes that on GitHub, in another page. Opening the install page in a new tab left them on a bare GitHub
page with a stale picker behind it. Instead the picker's install links go through `/install` in the same tab, which
sends the browser to `https://github.com/apps/<slug>/installations/new?state=<nonce>`; GitHub passes `state` through
to the setup URL untouched, and `/setup` sends the browser back to the picker of the Organization the trip started
from, which lists afresh. `setup_action=request` (the operator could only ask that account's admins to install) is
reported on the picker as pending rather than as an unchanged list. The `installation_id` GitHub appends is
deliberately unread: GitHub documents that it can be spoofed, and the picker lists installations with the
operator's token anyway.

The `state` parameter is a random nonce and nothing else. The Organization it belongs to travels in the encrypted,
path-scoped, `Lax` state cookie alongside that nonce, never in the URL — a state that carried the Organization id
would be a value an attacker could choose, and the whole job of the pair is to prove this callback answers a
connection *this* browser started. The nonce is compared in constant time and the cookie is cleared before anything
else can fail.

## 11. Configuration

| Setting | What it is |
|---------|-----------|
| `github.appName` | The GitHub App's **URL slug** — what appears in `https://github.com/apps/<slug>`. The admin UI builds the "install the app" link from it. |
| `github.clientId` / `github.clientSecret` | The GitHub App's OAuth credentials. |
| `web.cookieEncryptionKey` | 32 bytes, base64, encrypting the OAuth state cookie. |

The GitHub App itself needs: a **user authorization callback URL** of exactly `<base URL>/app/oauth/github/callback`;
a **setup URL** of exactly `<base URL>/app/oauth/github/setup` with **Redirect on update** enabled, so changing an
installation's repositories returns to the picker too; **Request user authorization (OAuth) during installation**
left off, because with it on GitHub never uses the setup URL and returns installs to the callback URL instead (the
callback recognises that return by its `setup_action` and lands on the picker too, but the setup URL is the
documented path); **Expire user
authorization tokens** enabled, so refresh tokens are issued; and read access to the **contents** and **metadata**
of the repositories it is installed on. The setup URL is one value per App, so a development App must point at the
developer's own `http://localhost:8080` — it is a browser redirect, so localhost works.

`github.clientId` and `github.clientSecret` ship as `replace-me`. A checked-in default that happened to be a working
App would let anyone who cloned this repository act as it. FusionAuth never sees these credentials at all — the
Agency runs the handshake itself and stores the result in its own database, so nothing GitHub-related is
provisioned by Kickstart.

## 12. Testing

`GitHubClient` is one interface over everything the Agency asks GitHub for, so the whole of it is replaced in tests
by `FakeGitHubClient` — an in-memory GitHub, injected through `new Main(port, test, github)`. It is the only seam:
FusionAuth and Postgres are the real local ones, and the GitHub credentials these tests store are genuinely written
to and read back from the `organizations` columns. A suite that reached the real api.github.com would need a live
App, a live installation, and a network, and would still be measuring GitHub rather than the Agency.

The fake has no history: a repository is a mutable map of paths to bytes, and its head is the SHA-256 of that map.
That reproduces the property the poller actually depends on — the head moves if and only if *something* changed,
including a file the Brief does not map — so `putFile("README.md", …)` is exactly the "new commit, no new version"
scenario, without simulating Git.
