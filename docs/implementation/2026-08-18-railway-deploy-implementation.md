# Railway Deployment Implementation

**Goal:** Everything the repo needs to deploy to Railway per the design — the runtime bundle and its launcher, the
two Dockerfiles, the Railway config-as-code, the production listener bind, and the kickstart parameterization.
Implemented in one pass alongside the design.

**Spec:** `docs/design/2026-08-18-railway-deploy-design.md`. Section references below point into it.

The Railway side (creating the project, services, domains, and variables) is operator work, not repository work —
it is the runbook in §10 of the design and deliberately has no tasks here.

## Task 1 — Application changes (§5)

- [x] `Main`: the no-arg constructor resolves the port as `new Configuration().getInteger("PORT", PORT)` —
      env-and-system-property-only lookup, 8080 default.
- [x] `Main.health` (static, alphabetized before `missing`): plain `200 OK`, checks nothing by design.
      Registered as `GET /health` at the root, outside both authentication prefixes.
- [x] `Main.main()`: the listener is a ternary on `runtime.mode` — `production` binds every interface, everything
      else keeps the loopback bind. The comment carries both halves of the transport reasoning.

**Verification:** full suite passes (165/165). Bundle smoke test below exercises `/health` and the `PORT`
override for real.

## Task 2 — Bundle and deploy targets (§3, §4)

- [x] `project.latte`: `bundle` target (copied from `latte-java/app`) assembling `build/bundle` from `web/`,
      `build/jars/the-agency-<version>.jar`, `src/main/scripts/app.sh`, and the transitive compile/runtime
      dependencies; `deploy` target running `railway up --no-gitignore --ci`.
- [x] `src/main/scripts/app.sh`: `latte-java/app`'s launcher with the module changed to
      `dev.theagencyhq.agency/dev.theagencyhq.agency.Main`. The explicit per-jar module path (JTE's runtime javac)
      survives verbatim.
- [x] `Dockerfile` (repo root): `eclipse-temurin:25-jdk`, `COPY build/bundle/`, `CMD ["bash", "app.sh"]`.
- [x] `.dockerignore`: everything except `build/bundle`.
- [x] `.railwayignore`: everything except `build/bundle`, `Dockerfile`, `railway.json`.
- [x] `railway.json`: Dockerfile builder, `healthcheckPath: /health`, `numReplicas: 1`, restart on failure.

**Verification:** `latte bundle` produced `app.sh` + 16 jars (app jar plus 15 dependencies, no test/src jars) +
`web/`. The bundle ran with env-only configuration and `PORT=8090`: `/health` → 200 `OK`, `/` → 303 to
`/app/organizations/`, `/static/css/app.css` → 200, `/app/organizations/` → 302 to login. `docker build` of the
root Dockerfile succeeded and the image carries `/app/app.sh`, `/app/lib`, `/app/web`.

## Task 3 — FusionAuth image and kickstart parameterization (§6, §7)

- [x] `src/main/fusionauth/Dockerfile`: `FROM fusionauth/fusionauth-app:1.67.1` + `COPY kickstart/`, tag kept in
      lockstep with the compose file.
- [x] `src/main/fusionauth/railway.json`: Dockerfile builder, `healthcheckPath: /api/status`, one replica.
- [x] `kickstart/kickstart.json`: `#{ENV.*}` for the five secrets (`KICKSTART_ADMIN_PASSWORD`,
      `KICKSTART_AGENCY_CLIENT_SECRET`, `KICKSTART_API_KEY`, `KICKSTART_HANDLER_CLIENT_SECRET`,
      `KICKSTART_ORDINARY_PASSWORD`), the tenant issuer (`KICKSTART_TENANT_ISSUER`), and the Agency application's
      redirect/logout URLs (reusing `FUSIONAUTH_APP_THEME_APP_URL`). The `localhost:8081` test-suite redirect
      entries stay hardcoded (§7's accepted wart). Application/client IDs unchanged.
- [x] `docker-compose.yml`: the six `KICKSTART_*` variables passed through with compose defaults equal to the
      previously hardcoded values, so existing `.env` files and reprovisions are unchanged.

**Verification:** kickstart and both `railway.json` files parse as JSON. `docker compose --env-file .env.template
config` resolves every `KICKSTART_*` default to the old hardcoded value. `docker build` of the FusionAuth image
succeeded with `kickstart.json`, `emails/`, and `theme/` baked in. Not verified here: a live reprovision
(`docker compose down -v && up`) — it would destroy local FusionAuth state; the `#{ENV.*}` mechanism is the same
one the theme URLs already use.

## Task 4 — Docs

- [x] `README.md`: a Deploying section pointing at the design; the design listed under Design documents.
- [x] `CLAUDE.md`: `latte bundle`/`latte deploy` in the commands table; the loopback sentence now says the bind
      widens under `runtime.mode=production`.
