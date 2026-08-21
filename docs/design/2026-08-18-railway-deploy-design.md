# The Agency — Railway Deployment

## 1. Purpose

Deploy The Agency to Railway: the admin UI and Briefing API at `https://app.theagencyhq.dev`, FusionAuth at
`https://auth.theagencyhq.dev`, and PostgreSQL for both. This covers the runtime bundle and Dockerfiles, the
Railway service topology, production configuration, and the kickstart changes that make one FusionAuth
provisioning file serve both development and production.

This supersedes nothing. `docs/design/2026-08-08-github-brief-sources-design.md` removed the last obstacle — a
Brief source that had to live on the server's own filesystem — and this design is the deployment it made
possible. `MembershipService` already names the production origin (`https://app.theagencyhq.dev` under
`runtime.mode=production`); this design is what makes that mode real.

**What is deliberately not here.** No CI/CD — deploys run from a developer's machine with `latte deploy`, and a
GitHub Actions pipeline (build the bundle in CI, push an image to a registry, point Railway at it) is a later
design when manual deploys become the bottleneck. No autoscaling or multiple replicas — §9 explains why one
replica is currently a correctness requirement, not a cost choice. No log/metrics stack beyond what Railway
provides.

## 2. The shape of the deployment

One Railway project, four services:

| Service               | Source                                                | Public domain             |
|-----------------------|-------------------------------------------------------|---------------------------|
| `the-agency`          | `Dockerfile` at the repo root, shipped via CLI upload | `app.theagencyhq.dev`     |
| `agency-postgres`     | Railway PostgreSQL                                    | none (private networking) |
| `fusionauth`          | `src/main/fusionauth/Dockerfile` from the GitHub repo | `auth.theagencyhq.dev`    |
| `fusionauth-postgres` | Railway PostgreSQL                                    | none (private networking) |

```
                 browser / Handler daemon
                      │ https (TLS at Railway's edge)
        ┌─────────────┴─────────────┐
        ▼                           ▼
app.theagencyhq.dev         auth.theagencyhq.dev
  [the-agency]  ──────────►   [fusionauth]
        │        https           │
        │ private networking     │ private networking
        ▼                        ▼
 [agency-postgres]       [fusionauth-postgres]
```

TLS terminates at Railway's edge; inside the project, services listen on plain HTTP over a private network.
Latte HTTP already honors `X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-For`, so behind Railway's proxy
the request scheme is `https` and `Cookies#isSecureScheme` marks session cookies `Secure` — the exact condition
`Main`'s loopback-only bind existed to protect (§5).

The Agency talks to FusionAuth through the **public** URL, not `fusionauth.railway.internal`. The issuer baked
into every token must match what the app validates against, and the browser is redirected to the same host to
log in; splitting those across two names buys a few milliseconds on JWKS fetches (which happen once, at startup)
at the cost of two names for one thing. The databases, which no browser ever touches, use private networking.

## 3. The runtime bundle

Building inside Railway is the wrong fight: `latte build` depends on `codegen`, which needs a scratch PostgreSQL
at build time, plus the Latte CLI, a JDK 25, and npm in the builder image. Instead the build happens where it
already works — the developer's machine — and Railway receives a finished runtime, mirroring exactly how
`latte-java/app` deploys to Cloudflare.

A new `bundle` target in `project.latte` (copied from `latte-java/app`) assembles `build/bundle`:

```
build/bundle/app.sh   — launcher: builds the per-jar module path, execs dev.theagencyhq.agency.Main
build/bundle/lib/     — the-agency-<version>.jar and every compile/runtime dependency
build/bundle/web/     — JTE templates and static assets (including the built Tailwind CSS)
```

`app.sh` is `latte-java/app`'s launcher with the module name changed. Its one non-obvious job survives intact:
the module path is an explicit colon-separated list of every jar in `lib/`, not the `lib/` directory, because
JTE compiles templates at runtime with the in-process javac and derives that compiler's classpath from
`jdk.module.path` — a directory entry there makes template compilation fail with "package gg.jte.html does not
exist".

The `Dockerfile` at the repo root is runtime-only:

```dockerfile
FROM eclipse-temurin:25-jdk    # JDK, not JRE: JTE's runtime template compilation needs javac
WORKDIR /app
COPY build/bundle/ /app/
EXPOSE 8080
CMD ["bash", "app.sh"]
```

No configuration files ship in the bundle. `Configuration` consults environment variables before any file, every
key in `Main.REQUIRED_CONFIG` is validated at startup, and the only keys outside that list (`poller.enabled`,
`poller.intervalSeconds`, `PORT`) have code defaults — so production runs entirely on Railway variables, and a
missing one fails the deploy loudly at boot instead of quietly falling back to a development value.

## 4. The deploy path

```
latte deploy   →   latte bundle   →   railway up --no-gitignore --ci
```

`railway up` scans, compresses, and uploads the working directory, and Railway builds the root `Dockerfile` from
the upload. Two dot-files shape that upload:

- **`.railwayignore`** — `build/` is gitignored, so the bundle would never upload without `--no-gitignore`; with
  it, everything would. This file (gitignore syntax) excludes everything except `Dockerfile` and `build/bundle`.
  The IaC file (§8) is read by `railway config`, never by `railway up`, so it stays out of the upload.
- **`.dockerignore`** — same shape, for anyone running `docker build` locally; Railway's upload is already
  filtered.

The service's healthcheck, replica count, domain, and variables are not part of the upload at all — they are
declared in `.railway/railway.ts`, the project's Infrastructure as Code (§8). One-time setup per machine:
`railway link` to bind the directory to the project and the `the-agency` service.

## 5. Application changes

Three changes to `Main`, no new abstractions:

1. **The bind widens in production.** `main()` currently binds the loopback interface unconditionally, because
   with no TLS listener, session cookies over plain HTTP off-loopback would travel in the clear. On Railway the
   TLS listener exists — at the edge — and the container must accept the proxy's connections, so
   `runtime.mode=production` binds all interfaces (`new HTTPListenerConfiguration(port)`) while every other mode
   keeps the loopback bind and its reasoning. `runtime.mode` is already a required, closed-set setting, so a
   typo fails at startup in `MembershipService` before the listener ever opens.
2. **The port comes from `PORT`.** The no-argument constructor resolves the port as
   `new Configuration().getInteger("PORT", PORT)` — env-and-system-property-only lookup, 8080 default. Railway's
   domain is configured with target port 8080 either way; reading `PORT` costs one line and removes the trap if
   the platform ever assigns one.
3. **`GET /health`** returns `200 OK`, installed at the root outside both authentication prefixes, mirroring
   `latte-java/app`. Railway's healthcheck gates each deploy on it; the existing `/` route answers with a 303
   redirect, which a healthcheck reads as failure. It deliberately touches nothing: a `Main` that constructed at
   all has already proven the database (migrations ran) and FusionAuth (discovery + JWKS), so a listening server
   is the health being asserted.

## 6. FusionAuth on Railway

The compose stack bind-mounts the kickstart directory; Railway has no bind mounts, so a three-line Dockerfile at
`src/main/fusionauth/Dockerfile` bakes it into the image:

```dockerfile
FROM fusionauth/fusionauth-app:1.67.1
COPY kickstart/ /usr/local/fusionauth/kickstart/
```

The `fusionauth` Railway service builds it straight from the GitHub repo with its root directory set to
`src/main/fusionauth` — kickstart changes deploy by push, which is safe because kickstart only ever applies to
an empty database. The GitHub source, the root directory, the `/api/status` healthcheck (the same endpoint the
compose healthcheck uses), and the 9011 target port are all declared in `.railway/railway.ts` (§8).

Differences from the compose stack, all environment-driven:

- **`SEARCH_TYPE=database`** — no OpenSearch service. It is the heaviest container in the compose stack, exists
  locally only because the compose file wires it, and FusionAuth's database search is sufficient at this scale.
- **No Mailcatcher** — §7 covers production email.
- **No volumes** — FusionAuth's state lives in its database; the config volume in compose only caches what the
  environment variables already say.
- **`FUSIONAUTH_APP_URL=https://auth.theagencyhq.dev`** and the theme URLs point at the production app origin.

FusionAuth's maintenance mode creates its own database and service user on first boot using the root
credentials, exactly as it does locally — `DATABASE_ROOT_USERNAME`/`DATABASE_ROOT_PASSWORD` come from the
`fusionauth-postgres` service's superuser, and `DATABASE_USERNAME`/`DATABASE_PASSWORD` are the service account
it creates.

## 7. One kickstart, two environments

`kickstart.json` hardcodes development values that must differ in production: the tenant issuer, the Agency
application's redirect URLs, both client secrets, the API key, and both seeded users' passwords. Kickstart has
no conditionals, so the file is parameterized with `#{ENV.*}` — the mechanism the theme URLs already use — and
each environment supplies its own values.

| Kickstart value                    | Environment variable              | Development (compose default)      | Production (Railway variable) |
|------------------------------------|-----------------------------------|------------------------------------|-------------------------------|
| Tenant issuer                      | `KICKSTART_TENANT_ISSUER`         | `http://localhost:9016`            | `https://auth.theagencyhq.dev` |
| Agency app redirect/logout URLs    | `FUSIONAUTH_APP_THEME_APP_URL`    | `http://localhost:8080` (existing) | `https://app.theagencyhq.dev` |
| Agency client secret               | `KICKSTART_AGENCY_CLIENT_SECRET`  | current dev secret                 | generated, strong             |
| Handler client secret              | `KICKSTART_HANDLER_CLIENT_SECRET` | current dev secret                 | generated, strong             |
| API key                            | `KICKSTART_API_KEY`               | current dev key                    | generated, strong             |
| Admin password                     | `KICKSTART_ADMIN_PASSWORD`        | `password`                         | generated, strong             |
| Ordinary user password             | `KICKSTART_ORDINARY_PASSWORD`     | `password`                         | generated, strong             |

The redirect URLs reuse `FUSIONAUTH_APP_THEME_APP_URL` rather than adding a second variable, because the theme's
"app URL" and the OAuth application's origin are the same fact — the Agency's own address as the browser sees
it.

Development supplies the current values as **compose-file defaults** (`${KICKSTART_ADMIN_PASSWORD:-password}`),
so existing `.env` files keep working untouched and a fresh `docker compose down -v && up` reprovisions
identically to today. The application and client IDs stay the same UUIDs in both environments — they are names,
not secrets, and keeping them means one less pair of values to thread through configuration.

Two accepted warts, chosen over their alternatives:

- **The test-suite redirect URLs (`http://localhost:8081/...`) stay hardcoded** and therefore exist in the
  production application's authorized list. Kickstart cannot conditionally omit array entries; a redirect to the
  operator's own loopback is a minor, documented exposure; and the alternative — unset variables leaving
  unresolved `#{ENV...}` literals in production config — is worse.
- **The seeded ordinary user (`user@theagencyhq.dev`) exists in production**, inert behind a generated password.
  Same reason: kickstart cannot conditionally skip a request.

**Email.** Kickstart keeps pointing the tenant at Mailcatcher — those are the right development values and SMTP
credentials do not belong in a provisioning file. In production, tenant SMTP is configured once by hand in the
FusionAuth admin UI against a real provider (Postmark, Resend, SES — operator's choice). Until that is done,
invitation emails fail visibly in FusionAuth's logs; nothing else is affected. This is a runbook step (§10), not
code.

## 8. Production configuration: Infrastructure as Code

Railway deprecated per-service config-as-code (`railway.json` / `railway.toml`): new services cannot opt into
it, and existing files stop being read on 2026-12-01. Its replacement, Infrastructure as Code, is a better fit
anyway — one `.railway/railway.ts` at the repo root declares the entire §2 topology (services, databases,
domains, replicas, healthchecks, variables), and `railway config plan` / `railway config apply` diff and apply
it against the linked project. The file is authored against the `railway` npm SDK; a `package.json` inside
`.railway/` keeps that dependency isolated from the Java project (Node and npm are already build prerequisites
via Tailwind), and the Railway CLI is the file's only consumer — nothing about the app's runtime changes.

**Where values live.** One rule decides what is a literal in the file and what is not: facts fixed by this
design (URLs, application UUIDs, runtime modes, memory) are literals; anything minted during the first deploy
(the §10.1 secrets, the GitHub App identity, the license key) is a Railway **shared variable**, set once at the
project level and referenced from the file as `shared.*`. The file is therefore committable as-is — it
names every secret but contains none — and each secret still lives in exactly one place: `FUSIONAUTH_API_KEY`
is one shared variable that the `fusionauth` service exposes to kickstart as `KICKSTART_API_KEY` and the
`the-agency` service reads as `FUSIONAUTH_APIKEY`. Database credentials are typed references (`agencyPostgres.env.PGPASSWORD`), which also
records a dependency edge from the service to its database; only the composed JDBC URLs fall back to Railway's
`${{service.VAR}}` template syntax as literal strings, because a typed reference cannot be embedded in a larger
string. Either way the file holds references, not values.

`Configuration` maps `db.password` → `DB_PASSWORD` (uppercase, non-alphanumerics to underscores), so every
setting the app reads is an environment variable on its service. The complete file:

The file is `.railway/railway.ts` in the repository — the listing below is the real file, minus the copyright
header:

```ts
import { createRailwayContext, defineRailway, github, postgres, project, service } from "railway/iac";

// Every resource is pinned to US East (Virginia); the other US region is us-west2 (California).
const REGION = "us-east4-eqdc4a";

export default defineRailway((ctx) => {
  // CLI runners before 5.43 invoke this program without a context; build one so `shared` always exists.
  const { shared } = ctx?.shared ? ctx : createRailwayContext();
  const agencyPostgres = postgres("agency-postgres", { region: REGION });
  const fusionauthPostgres = postgres("fusionauth-postgres", { region: REGION });

  const fusionauth = service("fusionauth", {
    build: {
      builder: "DOCKERFILE",
      // App-only pushes to main must not rebuild FusionAuth.
      watchPatterns: ["/src/main/fusionauth/**"],
    },
    deploy: {
      region: REGION,
      restartPolicyType: "ON_FAILURE",
    },
    domains: [{ domain: "auth.theagencyhq.dev", port: 9011 }],
    env: {
      DATABASE_PASSWORD: shared.FUSIONAUTH_DATABASE_PASSWORD,
      DATABASE_ROOT_PASSWORD: fusionauthPostgres.env.PGPASSWORD,
      DATABASE_ROOT_USERNAME: fusionauthPostgres.env.PGUSER,
      DATABASE_URL: "jdbc:postgresql://${{fusionauth-postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/fusionauth",
      DATABASE_USERNAME: "fusionauth",
      FUSIONAUTH_APP_KICKSTART_FILE: "/usr/local/fusionauth/kickstart/kickstart.json",
      FUSIONAUTH_APP_LICENSE_KEY: shared.FUSIONAUTH_APP_LICENSE_KEY,
      FUSIONAUTH_APP_MEMORY: "512M",
      FUSIONAUTH_APP_RUNTIME_MODE: "production",
      FUSIONAUTH_APP_THEME_APP_URL: "https://app.theagencyhq.dev",
      FUSIONAUTH_APP_THEME_CSS_URL: "https://app.theagencyhq.dev/static/css/app.css",
      FUSIONAUTH_APP_URL: "https://auth.theagencyhq.dev",
      KICKSTART_ADMIN_PASSWORD: shared.FUSIONAUTH_ADMIN_PASSWORD,
      KICKSTART_AGENCY_CLIENT_SECRET: shared.FUSIONAUTH_AGENCY_CLIENT_SECRET,
      KICKSTART_API_KEY: shared.FUSIONAUTH_API_KEY,
      KICKSTART_HANDLER_CLIENT_SECRET: shared.FUSIONAUTH_HANDLER_CLIENT_SECRET,
      KICKSTART_ORDINARY_PASSWORD: shared.FUSIONAUTH_ORDINARY_PASSWORD,
      KICKSTART_TENANT_ISSUER: "https://auth.theagencyhq.dev",
      SEARCH_TYPE: "database",
    },
    healthcheck: "/api/status",
    replicas: 1,
    source: github("the-agency-hq/the-agency", { branch: "main", rootDirectory: "src/main/fusionauth" }),
  });

  const theAgency = service("the-agency", {
    // No source: deploys arrive from `latte deploy` / `railway up` (design §4).
    build: {
      builder: "DOCKERFILE",
    },
    deploy: {
      region: REGION,
      restartPolicyType: "ON_FAILURE",
    },
    domains: [{ domain: "app.theagencyhq.dev", port: 8080 }],
    env: {
      DB_PASSWORD: agencyPostgres.env.PGPASSWORD,
      DB_URL: "jdbc:postgresql://${{agency-postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/${{agency-postgres.PGDATABASE}}",
      DB_USERNAME: agencyPostgres.env.PGUSER,
      FUSIONAUTH_APIKEY: shared.FUSIONAUTH_API_KEY,
      FUSIONAUTH_BASEURL: "https://auth.theagencyhq.dev",
      FUSIONAUTH_CLIENTID: "7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742",
      FUSIONAUTH_CLIENTSECRET: shared.FUSIONAUTH_AGENCY_CLIENT_SECRET,
      FUSIONAUTH_HANDLERCLIENTID: "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73",
      FUSIONAUTH_HANDLERCLIENTSECRET: shared.FUSIONAUTH_HANDLER_CLIENT_SECRET,
      FUSIONAUTH_ISSUER: "https://auth.theagencyhq.dev",
      GITHUB_APPNAME: shared.GITHUB_APPNAME,
      GITHUB_CLIENTID: shared.GITHUB_CLIENTID,
      GITHUB_CLIENTSECRET: shared.GITHUB_CLIENTSECRET,
      RUNTIME_MODE: "production",
      WEB_COOKIEENCRYPTIONKEY: shared.WEB_COOKIEENCRYPTIONKEY,
    },
    healthcheck: "/health",
    replicas: 1,
  });

  return project("The Agency HQ", {
    resources: [agencyPostgres, fusionauthPostgres, fusionauth, theAgency],
  });
});
```

Notes on the file:

- **The `build` and `deploy` blocks carry everything `railway.json` used to** — Dockerfile builder, restart on
  failure — plus the `fusionauth` watch paths, which under config-as-code were a dashboard-only setting. The
  `healthcheck` and `replicas` shorthands merge into the same deploy config.
- **Every resource is pinned to `us-east4-eqdc4a` (US East, Virginia)** through the one `REGION` constant —
  services via `deploy.region`, databases via the `postgres()` config — rather than inheriting the deploying
  account's preferred-region setting. Both databases and their volumes land beside the services they serve.
- **The `port` on each domain is the container target port** (where the process listens: FusionAuth on 9011,
  the app on 8080), not the public port — both domains are served on 443, HTTPS terminating at the edge (§2).
- The `fusionauth` env is the compose stack's variables minus the compose-only ones (`FUSIONAUTH_LOCAL_*`,
  `OPENSEARCH_JAVA_OPTS`, `SEARCH_SERVERS`), with `SEARCH_TYPE=database`, production mode, the production theme
  URLs, and the seven `KICKSTART_*` values from §7.
- `FUSIONAUTH_ISSUER` and `KICKSTART_TENANT_ISSUER` must stay the same literal — the token issuer and what the
  app validates against are one fact.

Because `railway config apply` creates the `fusionauth` service together with its complete environment, the §7
kickstart trap — a first boot with partial variables — is unreachable by construction, provided every shared
variable exists before the first apply. §10.4 orders the runbook accordingly.

## 9. Operational constraints

- **Exactly one replica of `the-agency`.** `PollerService` has no leader election; N replicas poll every source
  N times concurrently. The insert path tolerates it (checksums skip unchanged content) but duplicate versions
  become possible. `.railway/railway.ts` pins `replicas: 1`; scaling out is a future design (leader election or
  extracting the poller).
- **Deploys are not zero-downtime-guaranteed.** Railway overlaps old and new instances when healthchecks are
  configured, but two instances of the app briefly polling at once is the same story as replicas — acceptable
  for the same checksum reason, worth knowing about.
- **FusionAuth upgrades** are image-tag bumps in `src/main/fusionauth/Dockerfile`, which also updates the
  compose stack — one version, both environments.
- **Database backups** are Railway's native Postgres backups on both database services; nothing app-side.

## 10. First-deploy runbook

Steps run in order. Railway UI references are as of August 2026; the dashboard is `railway.com`.

### 10.1 Prerequisites

1. A Railway account with a workspace, signed in at `railway.com`.
2. The Railway CLI on the deploying machine, current enough to have the `railway config` commands:
   `brew install railway` (or `brew upgrade railway`), then `railway login` (opens the browser).
3. The IaC SDK: `cd .railway && npm install` — installs the `railway` npm package that `railway.ts` imports
   (§8). Node and npm are already on the machine as build prerequisites.
4. Access to the Cloudflare account holding the `theagencyhq.dev` zone — two subdomains get proxied CNAME + TXT
   records (10.5 step 3).
5. Generate the production secrets once, locally, and keep them in a password manager until they are pasted into
   Railway in step 10.4:

   | Secret                            | Generate with                                                                                        |
   |-----------------------------------|------------------------------------------------------------------------------------------------------|
   | `FUSIONAUTH_ADMIN_PASSWORD`       | `openssl rand -base64 18`                                                                            |
   | `FUSIONAUTH_AGENCY_CLIENT_SECRET` | `openssl rand -base64 32`                                                                            |
   | `FUSIONAUTH_API_KEY`              | `uuidgen \| tr 'A-Z' 'a-z'`                                                                          |
   | `FUSIONAUTH_DATABASE_PASSWORD`    | `openssl rand -hex 24`                                                                               |
   | `FUSIONAUTH_HANDLER_CLIENT_SECRET`| `openssl rand -base64 32`                                                                            |
   | `FUSIONAUTH_ORDINARY_PASSWORD`    | `openssl rand -base64 18`                                                                            |
   | `WEB_COOKIEENCRYPTIONKEY`         | `openssl rand -base64 32` (must be exactly 32 bytes of base64 — `Cookies.encryptionKeys` decodes it) |

### 10.2 Project and link

1. On the Railway dashboard, click **New Project**. Name it `The Agency HQ` — the project name is a display
   name; `railway config apply` targets the linked project regardless, unlike **service** names, which the
   `${{...}}` variable references resolve by and which must match `railway.ts` exactly. If the workspace has
   never connected GitHub, install Railway's GitHub App on the org now and grant it this repository — the
   `github()` source in `railway.ts` needs it before the apply in 10.5.
2. From the repo root: `railway link` — pick the workspace, the `the-agency` project, and the production
   environment. The link is stored per-directory and is what `railway config` and `railway up` operate on.

### 10.3 The production GitHub App

Per `README.md`'s requirements, on github.com: **Settings** → **Developer settings** → **GitHub Apps** →
**New GitHub App**:

- Callback URL: `https://app.theagencyhq.dev/app/oauth/github/callback`
- **Expire user authorization tokens**: enabled
- Repository permissions: **Contents: Read-only**, **Metadata: Read-only**
- Generate a client secret and note the app's slug (the name as it appears in its URL), client ID, and secret —
  they become the `GITHUB_APPNAME`, `GITHUB_CLIENTID`, and `GITHUB_CLIENTSECRET` shared variables in 10.4.

### 10.4 Shared variables

Project **Settings** → **Shared Variables**, production environment — add every value the `shared.*`
references in `railway.ts` name, filling in the generated values from 10.1 and the GitHub App values from 10.3:

```
FUSIONAUTH_ADMIN_PASSWORD=<generated>
FUSIONAUTH_AGENCY_CLIENT_SECRET=<generated>
FUSIONAUTH_API_KEY=<generated>
FUSIONAUTH_APP_LICENSE_KEY=<license key, or any placeholder string without one>
FUSIONAUTH_DATABASE_PASSWORD=<generated>
FUSIONAUTH_HANDLER_CLIENT_SECRET=<generated>
FUSIONAUTH_ORDINARY_PASSWORD=<generated>
GITHUB_APPNAME=<slug from 10.3>
GITHUB_CLIENTID=<from 10.3>
GITHUB_CLIENTSECRET=<from 10.3>
WEB_COOKIEENCRYPTIONKEY=<generated>
```

**All of it goes in before the first apply.** Kickstart runs exactly once, against the empty database, with
whatever environment the container has at that moment — a `fusionauth` service created while one of its shared
variables is missing would provision empty secrets, and the only recovery is dropping the `fusionauth`
database. With every shared variable in place first, 10.5's apply creates the service with its complete
environment and that state is unreachable (§8).

### 10.5 Apply the infrastructure

1. From the repo root: `railway config plan`. Review the preview: two Postgres services, `fusionauth`,
   `the-agency`, both custom domains, and the §8 variables.
2. `railway config apply`. Railway creates everything; `fusionauth` starts building from the GitHub repo
   immediately, while `the-agency` has no source and sits idle until 10.7.
3. DNS, in the Cloudflare dashboard for the `theagencyhq.dev` zone. Each Railway service's **Settings** →
   **Networking** shows a **CNAME** record and a **TXT** record for its custom domain; in Cloudflare **DNS** →
   **Records**, per service:
   - Add the CNAME (`app` → the `the-agency` target, `auth` → the `fusionauth` target, each something like
     `xxxxxx.up.railway.app`) with **Proxy status: Proxied** (orange cloud) — Railway requires the proxy: "If
     proxying is not enabled, Cloudflare will not associate the domain with your Railway project."
   - Add the TXT record exactly as shown — without it, requests 404 even after the CNAME resolves.

   Then, once for the zone: **SSL/TLS** → **Overview** → encryption mode **Full** — not Flexible (redirect
   loops) and not Full (strict), which Railway warns "will not work as intended"; and **SSL/TLS** →
   **Edge Certificates** → **Universal SSL** on (the default). Railway shows "Cloudflare proxy detected" with a
   green checkmark when a domain verifies; propagation is usually minutes, up to 72 hours.
4. Watch `fusionauth`'s **Deployments** tab → **Deploy Logs**: FusionAuth enters maintenance mode, creates its
   schema, then logs the kickstart requests. The deploy goes healthy when `/api/status` answers. If the very
   first boot loses the race with `fusionauth-postgres` provisioning and fails on the database connection,
   redeploy it — kickstart has not run against anything yet.
5. Verify from a terminal:

   ```
   curl -fs https://auth.theagencyhq.dev/.well-known/openid-configuration
   ```

   must report `"issuer":"https://auth.theagencyhq.dev"`. If it still says `localhost`, kickstart ran with the
   wrong environment — see the warning in 10.4.

### 10.6 Tenant SMTP (§7)

1. Sign in at `https://auth.theagencyhq.dev` as `admin@theagencyhq.dev` / `FUSIONAUTH_ADMIN_PASSWORD`.
2. **Tenants** → **Default** → edit → **Email** tab → fill in the SMTP host, port, username, password, and
   security setting from the chosen provider (Postmark, Resend, SES — the account and its sending domain
   verification for `theagencyhq.dev` are provider-side work, done first).
3. Use **Send test email** on the same screen, and confirm it arrives.

### 10.7 First deploy from the CLI

From the repo root on the deploying machine:

```
railway service   # pick the the-agency service — this is what `railway up` targets from now on
latte deploy      # bundle + railway up --no-gitignore --ci
```

`railway service` is one-time per machine, like the link in 10.2. `latte deploy` streams the Docker build; the
dashboard's **Deployments** tab shows the healthcheck on `/health` gating the cutover, and the app's boot log
shows the migrations applying.

### 10.8 Smoke test

1. `curl -fs https://app.theagencyhq.dev/health` → `OK`.
2. `https://app.theagencyhq.dev/` in a browser → redirects to the FusionAuth login page, themed like the app.
3. Sign in as `admin@theagencyhq.dev`. Create an Organization, connect a repository (the GitHub App from 10.3
   installs on it along the way), and confirm a Brief builds — the full §2 workflow of the GitHub Brief sources
   design, now against production.
4. Invite a member by email and confirm the invitation arrives (proves 10.6).
5. On both Postgres services, open the **Backups** tab and confirm scheduled backups are on (§9).
