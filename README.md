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

## Running the tests

The tests need PostgreSQL (`the_agency_test`) and the FusionAuth above. They authenticate as
`admin@theagencyhq.dev` through two real authorization-code flows, once per suite — one per Application.

    latte test

## Design documents

- `docs/design/2026-07-30-brief-pipeline-design.md` — the Brief pipeline and the Briefing API (milestone 1)
- `docs/design/2026-08-06-oauth-authentication-design.md` — OAuth authentication for the Briefing API
