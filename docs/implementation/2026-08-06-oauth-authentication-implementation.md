# OAuth Authentication Implementation Plan

**Goal:** Authenticate both faces of the Agency against a local FusionAuth on port 9016 provisioned by Kickstart —
the Briefing API with OAuth access tokens in place of static bearer tokens (Tasks 1–7), and the admin UI with a
browser session in place of nothing at all (Task 8).

Tasks run in order and later ones revise earlier ones: Task 2 introduces `fusionauth.clientId` for the Handler
Application, and Task 8 renames it to `fusionauth.handlerClientId` once there are two clients to tell apart.

**Spec:** `docs/design/2026-08-06-oauth-authentication-design.md`. Section references below point into it.

## Global Constraints

- **SPDX copyright header** first in every `.java` file, no blank line above it.
- **2-space indent**, 4-space continuation, 120-column target, alphabetized members/imports/`requires`/dependencies.
- **Acronyms fully uppercase** in identifiers (`OIDC`, `JWT`, `apiOIDC`).
- **Runtime values in square brackets** in every error and log message.
- **Never log an access token**, at any level.
- Tests are TestNG in `dev.theagencyhq.agency.tests`.

## Environment

- Docker running. Ports 9012, 9013, 9015 are already taken by other FusionAuth instances; 9016 is free.
- PostgreSQL on `127.0.0.1:5432`; `the_agency` and `the_agency_test` already exist.
- `latte` on the PATH.

## Task 1 — Local FusionAuth on 9016

- [ ] `src/main/fusionauth/docker-compose.yml`: copy the Handler's (`~/dev/the-agency-hq/handler/src/test/fusionauth`),
      rename the Compose project to `fusionauth-theagencyhq-agency`, publish `9016:9011`.
- [ ] `src/main/fusionauth/.env`: same variables as the Handler's. Committed (§5); no license key, no secret.
- [ ] `src/main/fusionauth/plugins/.gitkeep`.
- [ ] `src/main/fusionauth/kickstart/kickstart.json` per §5.1: tenant issuer `http://localhost:9016`, RS256 key,
      JWT-populate lambda, Application `fa83bc7c-f1c5-48af-8ecb-6c09cf766d73` (`clientAuthenticationPolicy:
      NotRequired` with a fixed client secret, PKCE required, `http://127.0.0.1:*/callback` with `AllowWildcards`),
      API key, and `admin@theagencyhq.dev` / `password` registered to both the FusionAuth admin Application (role
      `admin`) and the Handler Application.
- [ ] Bring it up: `cd src/main/fusionauth && docker compose up -d`, then wait for
      `curl -fs http://localhost:9016/api/status`.
- [ ] Verify Kickstart landed: `curl -fs http://localhost:9016/.well-known/openid-configuration` reports
      `"issuer":"http://localhost:9016"`, and `/.well-known/jwks.json` carries one RSA key.

**Verification:** both `curl`s succeed with the expected values.

## Task 2 — Build and configuration

- [ ] `project.latte`: add `org.lattejava:jwt:0.2.0` to the `compile` group (alphabetical). `Main` and
      `BriefingController` reference `JWT`/`OIDC` types, so the module must be on the compile module path.
- [ ] `src/main/java/module-info.java`: add `requires org.lattejava.jwt;`.
- [ ] `src/main/resources/config.properties`: drop `handler.tokens`, add `fusionauth.baseURL`,
      `fusionauth.clientId`, `fusionauth.clientSecret`, and `fusionauth.issuer`, and rewrite the header comment — it
      is currently almost entirely about guarding `handler.tokens`.
- [ ] `src/test/resources/config.properties`: drop `handler.tokens`. Add nothing; the tests use the same FusionAuth
      as the development server (§6).

**Verification:** `latte build` compiles.

## Task 3 — Wire the API profile in `Main`

- [ ] `REQUIRED_CONFIG` becomes `db.password, db.url, db.username, fusionauth.baseURL, fusionauth.clientId,
      fusionauth.clientSecret, fusionauth.issuer`.
- [ ] Add `public final OIDCConfig apiConfig` and `private final OIDC<JWT> apiOIDC`, built in the constructor per §7
      — a confidential client: client secret plus the introspection endpoint built from the base URL. Leave
      `scopes` at the library's default; scopes only affect flows the Agency initiates, not the validation of a
      token the Handler obtained on its own.
- [ ] Delete the private `tokens(Configuration)` method.
- [ ] Replace the flat `POST /api/v1/briefing` registration with the `/api` prefix carrying
      `apiOIDC.authenticated()`, per §7.
- [ ] Update the comment on the loopback listener so it still says what is true: the admin UI is the reason for the
      bind, and the API is no longer part of that argument.

**Verification:** `latte build`.

## Task 4 — `BriefingController`

- [ ] Drop the `Set<String> tokens` constructor parameter, the `BEARER` constant, the `authenticated` method, and the
      now-unused imports.
- [ ] Log the JWT subject with the response at `DEBUG` (§8).
- [ ] Rewrite the class Javadoc: authentication is a middleware concern now, and the class comment should say so.

**Verification:** `latte build`.

## Task 5 — Tests

- [ ] `BaseTest`: add `public static OIDCTestFixture oidc`, `public static String accessToken`, and
      `public static String refreshToken`; in `@BeforeSuite`, after `main.main()`, log in as
      `admin@theagencyhq.dev` against `http://127.0.0.1:8888/callback`, keep both tokens, then `oidc.logout()`
      (§10).
- [ ] `BaseTest`: add `protected WebTest authorized()` (or an equivalent helper) returning the tester with the
      `Authorization` header set, and document why one token serves the whole suite.
- [ ] `BriefingAPITest`: `briefing(...)` and `absentBodyIsTreatedAsAnEmptyAssertion` use the real token.
      `unknownTokenIsUnauthorized` and `missingTokenIsUnauthorized` stay as they are — both still assert `401`, now
      from the middleware.
- [ ] `BriefingAPITest`: add `malformedBodyWithoutTokenIsUnauthorized` — garbage body, no token, expect `401`.
- [ ] `BriefingAPITest`: add `expiredTokenWithARefreshTokenIsRefreshed` — an unverifiable access token plus
      `X-Refresh-Token`, expect `200`. The only coverage of the middleware's refresh path, where the Agency
      authenticates to FusionAuth as a confidential client.
- [ ] `PipelineIntegrationTest`: replace all five `Bearer test-token` headers.
- [ ] `src/test/java/module-info.java`: add any new `requires` the fixture pulls in.

**Verification:** `latte test` — the full suite green, with FusionAuth and PostgreSQL up.

## Task 6 — Documentation

- [ ] `README.md`: a "Local FusionAuth" section — how to start it, what Kickstart provisions, the
      `docker compose down -v` note, and that the tests need it running.
- [ ] `docs/design/2026-07-30-brief-pipeline-design.md`: amend decision 2, §2 (scope bullet), §6, and §10.1 to point
      at the new design rather than describe the retired scheme. Leave §10.2 and everything downstream alone.

**Verification:** re-read both documents; no remaining claim that the API validates a configured token list.

## Task 7 — `User` and `UserService`

- [ ] `model/User`: `record User(UUID userId, String email, String username)`. No `@JSON`; not a database row.
- [ ] `service/UserService`: `static User toUser(JWT)` over `sub`, `email`, `preferred_username`, throwing when
      `sub` is absent. Static, not registered on `Services`. Skip `app`'s FusionAuth-domain-user overload — it
      needs the `org.lattejava:fusionauth` dependency and has no caller here.
- [ ] `Main`: `OIDC.api(apiConfig, UserService::toUser)`, making the profile an `OIDC<User>`.
- [ ] `BriefingController`: take the profile, resolve `oidc.user()` **before** any work (not at the log statement,
      which runs after the body is written and so could not fail a request), log the `userId`.
- [ ] `UserServiceTest`: claim mapping, absent optional claims, missing `sub`, non-UUID `sub`.

**Verification:** `latte test`. Then break `toUser` deliberately and confirm the API tests fail — if they do not,
the resolution is not on the request path.

## Task 8 — SSR authentication for the admin UI

- [ ] Kickstart: a second Application, `The Agency` (`7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742`), confidential, with
      `/oidc/return` and `/oidc/logout-return` on ports 8080 and 8081, a `logoutURL`, the `admin`/`user` roles, and
      the JWT-populate lambda. Register `admin@theagencyhq.dev` for it. Re-provision with `down -v`.
- [ ] Config: rename the API keys to `fusionauth.handlerClientId` / `fusionauth.handlerClientSecret` and give the
      unprefixed pair to the new Application, as `app` names its own pair. All six join `REQUIRED_CONFIG`.
- [ ] `Main`: `ssrConfig`, `ssrSettings` (`postLoginPage` `/app/organizations/`, `postLogoutPage` `/`), `ssrOIDC`
      via `OIDC.ssr(..., UserService::toUser)`; install `OIDC.sessionEndpoints` at the root and
      `ssrOIDC.authenticated()` on the `/app` prefix.
- [ ] `OrganizationController`: take the profile; bind the viewer into every render through one private helper.
- [ ] Templates: `layout/main.jte` takes a `User viewer` and draws the name plus a `POST /logout` form; the five
      pages declare and forward it.
- [ ] `BaseTest`: a second fixture for the browser, which owns the cookie jar — never hand-built cookies.
      `AdminUITest` signs in once per test in its own `@BeforeMethod`; `AdminUIAuthenticationTest` calls
      `login()` per test because half its tests must be anonymous; `BaseTest` logs out in `@AfterMethod`, as
      `app`'s base class does. The API side keeps `authorized()` over the `Tokens` that `login()` returns, since a
      bearer header has no cookie state to manage.
- [ ] No `reset()` in a chain. `BaseTest.beforeMethod` clears request state so nothing carries between methods;
      the two places that must clear mid-method (`createOrganization`'s form fields, the `briefing(...)` helpers'
      headers) use `reset(ResetItem.Request)` or `clearRequestState()`, never the cookie-clearing variant.
- [ ] `AdminUIAuthenticationTest`: the gate, the session endpoints, the signed-in chrome, and the audience boundary
      in both directions.

**Verification:** `latte test`, then drive the real browser flow by hand against the development server — the test
fixture does its own token exchange and never walks through `/oidc/return`, so the callback is otherwise unproven.
Log out the same way, following the provider's meta-refresh back.

## Task 9 — Documentation

- [ ] `README.md`: the two Applications and what keeps them apart; how to sign in.
- [ ] `docs/design/2026-08-06-oauth-authentication-design.md`: retitle, and fold the browser half into §2, §3, §5.1,
      §6, §7, §8, §9, §10, §12.
- [ ] `docs/design/2026-07-30-brief-pipeline-design.md`: amend §11 and its localhost-bind justification.

## Task 10 — Commit

- [ ] `git add -A` and commit to `feat/oauth-tokens` with a Conventional Commit subject.

**Verification:** `git status` clean, `latte test` green from the committed tree.
