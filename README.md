# the-agency
The Agency web application that creates the Briefs that are then passed to the Handlers.

## Local FusionAuth

Both halves of the Agency authenticate against FusionAuth — the admin UI in the browser and the Briefing API for
Handlers — so a local instance has to be running before the server starts or the tests run:

    cd src/main/fusionauth && docker compose up -d

It comes up on `http://localhost:9016` with two Applications and the user `admin@theagencyhq.dev` / `password`
registered for both, all provisioned by Kickstart. Kickstart only runs against an empty database, so
re-provisioning after changing `kickstart.json` needs `docker compose down -v` first.

| Application           | Who uses it            | Why it is its own client                                        |
|-----------------------|------------------------|-----------------------------------------------------------------|
| `The Agency`          | The admin UI, in a browser | Confidential; the browser holds only a session cookie       |
| `The Agency Handler`  | The Handler daemon     | Public client, PKCE, no secret on the developer's machine       |

Keeping them separate is what stops a Handler's token from opening the admin UI, and an admin session from calling
the Briefing API: each profile requires its own client id in the token's `aud`.

The Handler Application id is the one the Handler ships compiled in, so this instance issues tokens the Agency
accepts and the Handler can obtain. Point the Handler at it by setting `authURL` in `handler.json`:

    "authURL": "http://localhost:9016"

Visiting <http://localhost:8080/> redirects to the admin UI, which redirects to FusionAuth's hosted login. Sign in
as `admin@theagencyhq.dev` / `password`; the "Sign out" control in the nav ends both sessions.

FusionAuth is a startup dependency, not just a request-time one: `Main` runs OIDC Discovery and fetches the JWKS
while it is constructing, so with FusionAuth down the Agency fails to start with `Failed to fetch OIDC discovery
document for issuer [...]` rather than starting up unable to validate a single token. Once it is running, nothing
in the request path calls FusionAuth — access tokens are JWTs, verified locally against the cached JWKS.

## Brief sources come from GitHub

An Organization's Briefs are built from a GitHub repository, polled through GitHub's REST API. There is no
local-filesystem source; registering one goes through the admin UI:

1. **New Organization**, and give it a name. You land on the Organization's page.
2. The page warns that the Organization is not connected to GitHub. **Connect to GitHub** authorizes The Agency's
   GitHub App and returns you to the page. The same warning comes back if the authorization is ever revoked or
   expires — reconnecting is the same button.
3. **Connect a repository**, and pick the repository and branch. It must have a `the-agency-hq-settings.json` at
   its root, which is checked before it is registered rather than on the first poll.

The credential that comes back is stored in columns on the Organization's own `organizations` row, so deleting the
Organization deletes it too. It is refreshed automatically; when it eventually lapses the source reports
`NOT_CONNECTED`, keeps serving the Brief it last published, and waits for someone to reconnect it.

Connecting needs a real GitHub App, so `github.clientId` and `github.clientSecret` ship as `replace-me` and have to
be overridden in `~/.config/the-agency-hq/the-agency/config.properties` or the environment. The App needs its user
authorization callback URL set to `http://localhost:8080/app/oauth/github/callback`, "Expire user authorization
tokens" enabled, and read access to the contents and metadata of the repositories it is installed on. Everything
else in the admin UI works without one.

## Running the tests

The tests need PostgreSQL (`the_agency_test`) and the FusionAuth above. They authenticate as
`admin@theagencyhq.dev` through two real authorization-code flows, once per suite — one per Application. GitHub is
the one thing they fake: `FakeGitHubClient` is an in-memory GitHub injected into `Main`, so no GitHub App and no
network are needed. The GitHub credentials they store are written to the real local Postgres.

    latte test

## Design documents

- `docs/design/2026-07-30-brief-pipeline-design.md` — the Brief pipeline and the Briefing API (milestone 1)
- `docs/design/2026-08-06-oauth-authentication-design.md` — OAuth authentication for the Briefing API
- `docs/design/2026-08-08-github-brief-sources-design.md` — Brief sources on GitHub, replacing local work trees
