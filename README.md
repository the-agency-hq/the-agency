# the-agency
The Agency web application that creates the Briefs that are then passed to the Handlers.

## Local FusionAuth

Both halves of the Agency authenticate against FusionAuth — the admin UI in the browser and the Briefing API for
Handlers — so a local instance has to be running before the server starts or the tests run. First time only, copy
the environment template and fill in your FusionAuth license key (`FUSIONAUTH_APP_LICENSE_KEY`). The key is
required: the Kickstart points the Applications at a custom account-management form, a licensed feature, so
without it the Applications fail to provision and nobody can log in:

    cd src/main/fusionauth && cp .env.template .env
    docker compose up -d

It comes up on `http://localhost:9016` with two Applications and two users — `admin@theagencyhq.dev` and
`user@theagencyhq.dev`, both `password` — registered for both, all provisioned by Kickstart. Kickstart only runs
against an empty database, so re-provisioning after changing `kickstart.json` needs `docker compose down -v` first.

The compose stack also runs Mailcatcher, and the Kickstart points the tenant's SMTP configuration at it: the
invitation and set-password emails the membership pages send land at <http://localhost:1080> instead of failing.
The email templates themselves live in `src/main/fusionauth/kickstart/emails/` and are provisioned by Kickstart
like everything else.

### The hosted pages wear the app's theme

Kickstart provisions a full advanced theme — all 52 FreeMarker templates, in
`src/main/fusionauth/kickstart/theme/` — so the login, password, error, and account pages look like the admin UI,
including the same three-state light/dark toggle. Two layers make that work:

- The pages link the admin UI's own stylesheet. The URL comes from the `FUSIONAUTH_APP_THEME_CSS_URL` environment
  variable at Kickstart time (`src/main/fusionauth/.env` sets the dev value,
  `http://localhost:8080/static/css/app.css`; production supplies `https://app.theagencyhq.dev/static/css/app.css`
  when it first deploys). The app serves `/static` with `Cross-Origin-Resource-Policy: cross-origin` precisely so
  this cross-origin link works.
- The theme's own stylesheet maps FusionAuth's semantic tokens (`--page-background`, `--primary-button`, …) onto
  the app's palette variables for light, and again under `.dark` for dark mode.

Tailwind compiles the theme's templates too (`@source` in `src/main/css/app.css`), so classes used only by the
theme still end up in `app.css`. Like everything Kickstart provisions, editing the theme files needs a
`docker compose down -v` to take effect. The account pages' Edit Profile is a custom self-service form holding
just the email — users have no username — and pointing an application at a custom form is what needs the license.

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
as `admin@theagencyhq.dev` / `password`; the "Sign out" control in the nav ends both sessions. Both Applications
also allow self-registration — the login page's "Create an account" link collects just an email and a password
through FusionAuth's basic registration, so a new user can sign up without being invited first.

FusionAuth is a startup dependency, not just a request-time one: `Main` runs OIDC Discovery and fetches the JWKS
while it is constructing, so with FusionAuth down the Agency fails to start with `Failed to fetch OIDC discovery
document for issuer [...]` rather than starting up unable to validate a single token. Once it is running, nothing
in the request path calls FusionAuth — access tokens are JWTs, verified locally against the cached JWKS.

## Organizations have members

Every Organization page sits behind a membership check: creating an Organization seats you as its ACTIVE `OWNER`,
and nobody else sees it until you invite them. **Members** on the Organization's page (Owners only) lists everyone
and offers the invite, change-role, and remove flows; invitees see the Organization in their listing with an
Accept/Decline banner on its page, and any active member can leave — except the last active Owner, who has to
promote someone first.

Inviting goes by email, through FusionAuth. An email FusionAuth already knows gets the invitation email; an
unknown one gets a FusionAuth account created for it, and the set-password email doubles as the invitation. Both
land in Mailcatcher locally.

Roles: an `OWNER` manages members and the GitHub connection; a `CONTRIBUTOR` views the Organization, its Briefs,
and can trigger rebuilds. The APIs follow the same boundary — `GET /api/v1/organization` and
`POST /api/v1/briefing` serve the caller's ACTIVE memberships, nothing more.

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

The tests need PostgreSQL (`the_agency_test`) and the FusionAuth above. They authenticate as the two Kickstart
users through real authorization-code flows — `admin@theagencyhq.dev` owns what a test creates, and
`user@theagencyhq.dev` is the one invited, promoted, and turned away by the membership tests. GitHub is
the one thing they fake: `FakeGitHubClient` is an in-memory GitHub injected into `Main`, so no GitHub App and no
network are needed. The GitHub credentials they store are written to the real local Postgres.

    latte test

## Deploying

Production runs on Railway: the app at `https://app.theagencyhq.dev`, FusionAuth at `https://auth.theagencyhq.dev`,
and a Railway PostgreSQL for each. The build happens on the developer machine — `latte deploy` assembles a
self-contained runtime bundle (`latte bundle` → `build/bundle`) and ships it with `railway up`; Railway builds the
runtime-only `Dockerfile` from the upload. FusionAuth deploys from this repo too (`src/main/fusionauth/Dockerfile`
bakes the kickstart into the image), and all production configuration is Railway variables. The topology, variable
tables, and first-deploy runbook are in `docs/design/2026-08-18-railway-deploy-design.md`.

## Design documents

- `docs/design/2026-07-30-brief-pipeline-design.md` — the Brief pipeline and the Briefing API (milestone 1)
- `docs/design/2026-08-06-oauth-authentication-design.md` — OAuth authentication for the Briefing API
- `docs/design/2026-08-08-github-brief-sources-design.md` — Brief sources on GitHub, replacing local work trees
- `docs/design/2026-08-18-railway-deploy-design.md` — the Railway deployment
