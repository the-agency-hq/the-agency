# The Agency — OAuth Authentication

## 1. Purpose

Authenticate both faces of the Agency against FusionAuth, using the same mechanism `latte-java/app` uses: Latte
Web's `org.lattejava.web.oidc` profiles, a local FusionAuth provisioned by Kickstart from a Docker Compose file in
the repository, and `OIDCTestFixture` driving real authorization-code flows in the tests.

- The **Briefing API** replaces its shared static bearer tokens with OAuth access tokens (`OIDC.api`).
- The **admin UI** replaces having no authentication at all with a browser session (`OIDC.ssr`).

This supersedes §10.1 (Authentication), the `handler.tokens` half of §6 (Configuration), and the "no
authentication" premise of §11 (Admin UI) of `docs/design/2026-07-30-brief-pipeline-design.md`. Everything else in
that document still stands — in particular the §10.2 decision matrix, which does not change at all.

## 2. Scope

**In scope**

- A local FusionAuth on port **9016**, defined by `src/main/fusionauth/docker-compose.yml` and provisioned by
  Kickstart with two Applications and the test user `admin@theagencyhq.dev` registered for both.
- `POST /api/v1/briefing` authenticates with `Authorization: Bearer <FusionAuth access token>`, validated locally
  against the IdP's JWKS by Latte Web's API-mode OIDC middleware.
- `/app/**` authenticates with a browser session: the four session endpoints (`/login`, `/oidc/return`, `/logout`,
  `/oidc/logout-return`), a redirect challenge for anonymous visitors, and the signed-in user in the page chrome
  with a sign-out control.
- Removal of `handler.tokens` and of `BriefingController`'s hand-rolled token comparison.
- Tests that obtain real tokens from the running FusionAuth rather than sending fixed strings.

**Out of scope — unchanged by this work**

- **Entitlements.** Milestone 1 decision 3 stands: a valid token is entitled to every Organization. There is no
  membership table and no `403`. §10.4 of the milestone-1 design still describes what changes when entitlements
  arrive, and this work moves exactly one item on that list — the token now resolves to a `User` (§8) — while
  leaving `entitled` defined as `listOrganizations()`.
- **Authorization inside the admin UI.** Every user registered for the Agency Application can do everything on
  those pages, exactly as `app` gates its own `/app` prefix on `authenticated()` alone. `requireRegistration` is
  the real gate: a FusionAuth user with no registration cannot obtain a token at all. A role check is a one-liner
  (`ssrOIDC.hasAnyRole("admin")`) if that ever stops being enough, and the `admin` role already exists (§5.1).
- **The loopback bind.** Still loopback-only, but for a different reason than before (§7).
- **The Handler.** `~/dev/the-agency-hq/handler` already implements `handler login` against FusionAuth and already
  sends `Authorization: Bearer` with a refresh-and-retry on `401`. It needs no change to talk to this API beyond
  pointing `authURL` at this instance (§4). It lives in its own repository and is not touched here.

## 3. Decisions

| # | Question                                | Decision                                                                                                                                                                                                             |
|---|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0 | One FusionAuth Application or two       | **Two**, as `app` has two. The audience check is the boundary: each profile requires its own client id in the token's `aud`, so a Handler's token cannot open the admin UI and an admin's session cannot call the Briefing API. One shared Application would make those silently interchangeable, and the two clients differ anyway — one is public with a loopback redirect, the other confidential with browser redirects. |
| 1 | Which OIDC profile                      | `OIDC.api(...)` for the Handler — `Authorization: Bearer` in, status codes out; a redirect challenge would be meaningless to a daemon. `OIDC.ssr(...)` for the admin UI — cookies in, redirects out.                  |
| 2 | Token validation mode                   | Local JWKS verification (`validateAccessToken=true`, the default). No introspection call per request, so the Agency stays up and fast while FusionAuth is unreachable mid-flight.                                     |
| 3 | Client identity                         | The Handler Application id `fa83bc7c-f1c5-48af-8ecb-6c09cf766d73` is reused from the Handler's own Kickstart — the `aud` check in `TokenValidator` compares against the configured client id, so any other value rejects every token the shipped Handler can obtain. The Agency Application is new here: `7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742`. |
| 4 | Public vs confidential client           | **Confidential on both**, exactly as `app` configures its pair. Only a server ever holds either secret. The Handler Application additionally has a public half — the daemon is a jar on a developer's machine, holds no secret, and proves possession with PKCE — which is why its `clientAuthenticationPolicy` is `NotRequired`: the Agency authenticates to FusionAuth with the secret while the Handler does not. |
| 5 | FusionAuth transport                    | Plain HTTP on `http://localhost:9016`. Latte's `Tools.requireSecureURI` permits `http` for loopback hosts precisely so local development needs no certificate. `app` uses HTTPS because it serves a browser-facing host name; the Agency is reached at `localhost` and does not. |
| 6 | Where the Compose file lives            | `src/main/fusionauth/`, as in `app` — the Agency needs FusionAuth to *run*, not only to test. (The Handler puts its copy under `src/test/` because only its tests need one.)                                          |
| 7 | Entitlements                            | Unchanged (decision 3 of the milestone-1 design). A valid token is entitled to every Organization.                                                                                                                    |
| 8 | Roles                                   | None enforced, as in `app`. `requireRegistration=true` on both Applications already means an unregistered user cannot obtain a token at all, so a role check on top would reject nobody who is not already rejected. The `admin` and `user` roles are provisioned anyway (§5.1) so adding one later is a one-liner rather than a Kickstart change. |
| 9 | Test credentials                        | `admin@theagencyhq.dev` / `password`, provisioned by Kickstart and registered for both Applications. Two authorization-code flows per suite — one per Application — not per request.                                  |
| 10 | Post-logout page                       | `/`, the Agency's own front door, not a marketing site. `app` sends a signed-out user to `lattejava.org`; `theagencyhq.dev` does not resolve yet, so copying that literally would hand every sign-out a browser error page. From `/` the gate bounces the visitor to the login screen. |

## 4. What the Handler already does — the fixed contract

The client is already written, so its behavior is a constraint rather than a choice
(`~/dev/the-agency-hq/handler/src/main/java/dev/theagencyhq/handler/auth/AuthConfiguration.java` and
`.../agency/AgencyClient.java`):

- **Client id** `fa83bc7c-f1c5-48af-8ecb-6c09cf766d73`, compiled in as a constant.
- **Scopes** `openid offline_access`. The refresh token is what keeps a daemon logged in.
- **Flow** authorization code + PKCE (S256) with a loopback redirect `http://127.0.0.1:<ephemeral>/callback`.
- **Transport** `Authorization: Bearer <access token>` on `POST /api/v1/briefing`.
- **On `401`** refresh once and retry; a second `401` is terminal and tells the developer to run `handler login`.
- **On `403`** treat the Organization set as revoked. Still unreachable from the Agency (§2).
- **On anything else** log and keep serving what it already holds, retrying next cycle. A `503` from the
  unavailable-IdP path therefore degrades to "try again later", which is the correct behavior for it.

The Agency's job is to accept exactly the token that flow produces. Everything in §5 and §6 follows from that.

## 5. Local FusionAuth

`src/main/fusionauth/` mirrors the Handler's copy of the same setup, with the Compose project renamed and the port
changed:

```
src/main/fusionauth/.env                     Compose variables — committed; contains no secrets
src/main/fusionauth/docker-compose.yml       Postgres + OpenSearch + FusionAuth, project fusionauth-theagencyhq-agency
src/main/fusionauth/kickstart/kickstart.json Tenant issuer, RSA key, lambda, Application, users
src/main/fusionauth/plugins/.gitkeep         Mounted plugin directory, empty
```

- **Port `9016`** published to the container's `9011`. The three FusionAuth instances on this machine
  (`latte-java/web` 9012, `latte-java/app` 9013, `the-agency-hq/handler` 9015) are untouched.
- Its **own Postgres container**, not the Agency's local Postgres. FusionAuth owns that schema and nothing else
  should share a database with it.
- `.env` is **committed**, unlike `app`'s. `app` gitignores it because it holds a FusionAuth license key; this one
  has no license key and no secret in it, and committing it means `docker compose up -d` works on a fresh clone with
  no copy-the-template step.

### 5.1 Kickstart

| Object              | Value                                                                                                  |
|---------------------|--------------------------------------------------------------------------------------------------------|
| Tenant issuer       | `http://localhost:9016` — becomes the `iss` claim, and must match `fusionauth.issuer`                  |
| Asymmetric key      | RS256, 2048, named `For The Agency`; signs both access and id tokens                                    |
| JWT populate lambda | Adds `email` and `preferred_username` to both Applications' tokens, as the Handler's and `app`'s do. `UserService` reads exactly those two claims, so without the lambda the chrome would have nothing to render |
| Application 1       | `The Agency`, id `7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742` — the admin UI                                   |
| — OAuth config      | Grants `authorization_code` + `refresh_token`, `generateRefreshTokens: true`, `requireRegistration: true`, a fixed client secret |
| — Redirect URLs     | `/oidc/return` and `/oidc/logout-return` on both `http://localhost:8080` (the development server) and `http://localhost:8081` (the suite's port), with `logoutURL` pointing at the 8080 logout-return. `app` registers both ports for exactly the same reason |
| — Roles             | `admin` (super) and `user` (default), provisioned but not enforced (decision 8)                          |
| Application 2       | `The Agency Handler`, id `fa83bc7c-f1c5-48af-8ecb-6c09cf766d73` — the Briefing API                       |
| — OAuth config      | `clientAuthenticationPolicy: NotRequired`, `proofKeyForCodeExchangePolicy: Required`, `consentMode: NeverPrompt`, grants `authorization_code` + `refresh_token`, `generateRefreshTokens: true`, `requireRegistration: true` |
| — Client secret     | Provisioned to a fixed dev value rather than left to FusionAuth's generated one, so it can be checked in alongside `config.properties`. `NotRequired` means the Handler can ignore it while the Agency authenticates with it (decision 4). |
| — Redirect URLs     | `http://127.0.0.1:*/callback` with `authorizedURLValidationPolicy: AllowWildcards` — the Handler binds an ephemeral port, and the test fixture uses `http://127.0.0.1:8888/callback` |
| API key             | `33052c8a-...-not-for-prod`, for reaching the FusionAuth admin API by hand                              |
| User                | `admin@theagencyhq.dev` / `password`, registered to the FusionAuth admin Application with the `admin` role **and** to both Applications above. `requireRegistration` on both means an unregistered user cannot log into either |

The Handler Application id is copied deliberately from the Handler's Kickstart (decision 3). The two Kickstarts
describe the same Application in two dev instances; when they are eventually pointed at one instance, the Handler's
compiled-in client id and the Agency's configured client id already agree.

Kickstart only runs against an empty database, so re-provisioning after editing `kickstart.json` needs
`docker compose down -v` first.

## 6. Configuration

`handler.tokens` is deleted. Six keys replace it, all required, named as `app` names its pair: the unprefixed
credential is the browser-facing Agency itself, and the prefixed one is the API client (`app` calls its API client
`cli` because it is a CLI; here it is the Handler).

```properties
fusionauth.baseURL=http://localhost:9016
fusionauth.clientId=7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742
fusionauth.clientSecret=super-secret-secret-that-should-be-regenerated-for-production
fusionauth.handlerClientId=fa83bc7c-f1c5-48af-8ecb-6c09cf766d73
fusionauth.handlerClientSecret=super-secret-secret-that-should-be-regenerated-for-production
fusionauth.issuer=http://localhost:9016
```

- `fusionauth.issuer` — the IdP's identity, and the value of the `iss` claim. OIDC Discovery runs against it at
  startup (`{issuer}/.well-known/openid-configuration`) and fills in the authorize, token, and JWKS endpoints, so
  those are not configured separately.
- `fusionauth.baseURL` — where FusionAuth is actually reachable. The same string as the issuer locally, and kept
  separate for the same reason `app` keeps it separate: the two need not agree in production. The introspection
  endpoint is built from it (`{baseURL}/oauth2/introspect`) because FusionAuth does not advertise introspection in
  its OpenID configuration, so Discovery cannot supply it.
- `fusionauth.clientId` / `fusionauth.clientSecret` — the Agency Application, behind which the admin UI sits.
- `fusionauth.handlerClientId` / `fusionauth.handlerClientSecret` — the Handler Application, behind which the
  Briefing API sits. The id is the one the Handler ships compiled in.
- Each client id is both the `client_id` on the flows that profile drives and the value its tokens' `aud` claim
  must contain, which is what keeps the two halves from opening each other (decision 0). Both secrets are real
  credentials used for HTTP Basic when the Agency talks to FusionAuth itself (decision 4): they belong in the
  override file or the environment anywhere that is not a local run.

There is deliberately no `fusionauth.apiKey` — the Agency never calls the FusionAuth admin API. (`app` has one
because its tests look users up through `FusionAuthClient` to seed memberships; there are no memberships here.)

`REQUIRED_CONFIG` in `Main` becomes `db.password, db.url, db.username, fusionauth.baseURL, fusionauth.clientId,
fusionauth.clientSecret, fusionauth.handlerClientId, fusionauth.handlerClientSecret, fusionauth.issuer`. The test configuration overrides none of the FusionAuth keys: the tests
authenticate against the same local provider the development server does, exactly as `app`'s tests do.

## 7. Wiring

`Main`'s constructor builds both profiles, mirroring `app`:

```java
this.ssrConfig = OIDCConfig.builder()
                           .clientId(config.get("fusionauth.clientId"))
                           .clientSecret(config.get("fusionauth.clientSecret"))
                           .introspectionEndpoint(URI.create(config.get("fusionauth.baseURL") + "/oauth2/introspect"))
                           .issuer(config.get("fusionauth.issuer"))
                           .build();
this.ssrSettings = BrowserSettings.builder()
                                  .postLoginPage("/app/organizations/")
                                  .postLogoutPage("/")
                                  .build();
this.ssrOIDC = OIDC.ssr(ssrConfig, ssrSettings, UserService::toUser);

this.apiConfig = OIDCConfig.builder()
                           .clientId(config.get("fusionauth.handlerClientId"))
                           .clientSecret(config.get("fusionauth.handlerClientSecret"))
                           .introspectionEndpoint(URI.create(config.get("fusionauth.baseURL") + "/oauth2/introspect"))
                           .issuer(config.get("fusionauth.issuer"))
                           .build();
this.apiOIDC = OIDC.api(apiConfig, UserService::toUser);
```

Both profiles leave `scopes` at the library's default (`openid`, `profile`, `email`, `offline_access`). Scopes only
affect flows the Agency itself initiates — the login the browser is redirected into and the ones the tests drive —
never the validation of a token the Handler obtained on its own, which is a signature and an audience check.

`validateAccessToken` is left at its default of `true`, so an access token is verified locally against the cached
JWKS and no request touches FusionAuth. The introspection endpoint is configured anyway, as `app` configures it:
opaque-token validation then becomes a one-line change rather than a new configuration key, and configuring it is
only meaningful for a confidential client, since RFC 7662 introspection requires the caller to authenticate.

The one place the credential is used today is the **refresh path**: when an access token fails verification and the
caller supplied `X-Refresh-Token`, the middleware exchanges it at the token endpoint with HTTP Basic.

`apiConfig`, `ssrConfig`, and `ssrSettings` are public fields for the same reason `app`'s are: `OIDCTestFixture`
takes a config (and, for the browser, the settings that name the cookies and paths) and drives the real flow with
it, so the tests need the ones the server is actually using rather than second copies that could drift.

Routes move under prefixes so each middleware is installed once rather than per route:

```java
.install(OIDC.sessionEndpoints(ssrConfig, ssrSettings))
.get("/", (_, res) -> res.sendRedirect("/app/organizations/", 303))
.prefix("/api", api -> api.install(apiOIDC.authenticated())
                          .post("/v1/briefing", briefing::briefing, BodySupplier.of(BriefingRequestJSON::fromJSON)))
.prefix("/app", app -> app.install(ssrOIDC.authenticated())
                          .prefix("/organizations", orgs -> { ... }))
```

`sessionEndpoints` is a middleware rather than four routes, installed at the root: the browser reaches `/login`,
`/oidc/return`, `/logout`, and `/oidc/logout-return` directly, and none of them can sit behind the gate they exist
to satisfy. `/` stays outside the gate too — it only redirects, and redirecting an anonymous visitor to a page that
will bounce them to login is the same outcome with one less special case.

Two properties of Latte Web make this the right shape:

- **Middleware runs before the body supplier** (`Web.route`: "The supplier is called after any middlewares"). An
  unauthenticated request carrying a malformed body gets `401`, not `400` — the server never parses a body it has no
  reason to trust.
- **`install` inside a `prefix` scopes the middleware to that literal prefix.** Every future `/api/**` and
  `/app/**` route is authenticated by construction; there is no per-route opt-in to forget.

**Startup couples the Agency to FusionAuth.** `OIDCConfig.build()` performs Discovery and `OIDC.api(...)` fetches the
JWKS, both in `Main`'s constructor, for each profile. With FusionAuth down, the Agency does not start — it fails
with "Failed to fetch OIDC discovery document for issuer [...]". This is `app`'s behavior too, and it is the honest
one: a server that boots without the ability to validate a single token would reject everyone while looking
healthy. Once started, nothing in the request path calls FusionAuth (decision 2), so a later outage costs nothing
until a token expires.

**The listener stays loopback-only, for a new reason.** The milestone-1 design bound it to loopback because the
admin UI had no authentication at all; that reason is now gone. What remains is transport: there is no TLS
listener, and Latte's `Cookies` marks a cookie `Secure` only on an https request (`Cookies#isSecureScheme`). Off
the loopback interface, over plain http, every admin session cookie would travel in the clear. The bind widens when
there is a TLS listener to widen it onto — not before.

## 8. `User`, `UserService`, and the controllers

`BriefingController` loses the `Set<String> tokens` constructor parameter, the `BEARER` constant, and the
`authenticated` method. The middleware has already validated the token by the time the handler runs, so the first
thing the method does is what it always should have: work.

What replaces the boolean is the identity the milestone-1 design §10.4 anticipated, as a domain object rather than
a bag of claims — the same two types `app` has, wired the same way:

- **`model.User`** — `record User(UUID userId, String email, String username)`. Not a database row; every member
  comes from a claim, so it is only ever as current as the token that carried it. `userId` is the only member
  anything should key on: it is stable across an email or username change, and it is what memberships will hang
  off.
- **`service.UserService`** — `static User toUser(JWT)`, mapping `sub`, `email`, and `preferred_username`. Static
  and not registered on `Services`, because it holds nothing. `app`'s second overload (translating a FusionAuth
  domain user) is deliberately not copied: it exists there to enrich members and invitees from admin-API lookups,
  which requires the `org.lattejava:fusionauth` dependency and has no caller here.

`Main` passes the translator to **both** profiles, which is what makes each an `OIDC<User>`:

```java
this.ssrOIDC = OIDC.ssr(ssrConfig, ssrSettings, UserService::toUser);
this.apiOIDC = OIDC.api(apiConfig, UserService::toUser);
```

One translator for two profiles is the point of having it: a browser session and a Handler token carry the same
three claims and produce the same record, so nothing downstream has to know which door the caller came through.

`BriefingController` takes that profile and resolves the caller first, before any work, as every controller in
`app` does:

```java
var user = oidc.user();
```

Nothing varies by user yet — `entitled` is still every Organization — so today it reaches only the response log
(`userId` alone: it is the least revealing of the three members and the one entitlements will key on). Resolving it
up front rather than at the log statement is deliberate: it puts the translation on the request path, so a token
the translator cannot map fails the request instead of being discovered after a `200` has already been written.
The access token itself is still never logged, at any level.

`OrganizationController` takes the browser profile for a different reason: the shared layout draws the signed-in
user in the page chrome, so every page needs the `User` as a template parameter alongside its own view model. A
private `render(...)` helper binds `Map.of("model", …, "viewer", oidc.user())` so no handler has to remember it —
forgetting is not a compile error but a template that fails to render.

That completes the first bullet of §10.4. The remaining two — `entitled` from memberships, and a reachable `403` —
still need tables that do not exist.

## 9. Responses

| Status | Meaning                                       | Who sets it                                                                    |
|--------|-----------------------------------------------|--------------------------------------------------------------------------------|
| `401`  | Missing, malformed, expired, or wrong-audience token | `StatusChallenge` via the `authenticated()` middleware, before the controller |
| `503`  | FusionAuth unreachable *and* a refresh was needed | `StatusChallenge.unavailable`                                                 |
| `400`  | Duplicate `organizationId` in `currentVersions`  | `BriefingController`, unchanged                                              |
| `304`  | Handler is current                              | `BriefingController`, unchanged                                               |
| `200`  | Briefs and the entitled Organization set        | `BriefingController`, unchanged                                              |
| `403`  | No entitlements                                 | Still never sent (§2)                                                         |

`503` is new. It is reachable only through the refresh path — with `validateAccessToken=true` a plain validation
failure needs no network — and the Handler already treats an unrecognized status as "retry next cycle", so it needs
nothing on the client side.

The admin UI answers in the browser's dialect instead, through `RedirectChallenge`:

| Response                     | When                                                                                       |
|------------------------------|--------------------------------------------------------------------------------------------|
| `302` → `/login`             | No session cookie at all, or a session that could not be refreshed. The challenge also drops an `oidc_return_to` cookie, so the callback lands the visitor on the page they asked for rather than the dashboard |
| `200` meta-refresh           | An access token that failed verification with no refresh token alongside it. A one-shot interstitial that retries the same URL with a marker parameter; the retry then redirects to `/login`. It exists for the SameSite cross-site navigation case |
| `503`                        | FusionAuth unreachable, via `BrowserSettings#unavailableHandler` |

## 10. Testing

The two halves ask for credentials in different ways, because the harness hands them over in different ways.

**The browser half follows `app`**: `OIDCTestFixture.login` drives a real authorization-code flow and stores the
issued tokens in the shared cookie jar itself. Nothing in the tests constructs a `Cookie`, and nothing replays one.

- `AdminUITest` signs in **once per test**, in its own `@BeforeMethod` — every page in that class is behind the
  gate, so putting it at the top of eight methods would be eight copies of the same line.
- `AdminUIAuthenticationTest` calls `login()` explicitly per test, because there the session *is* the subject:
  half its tests must be anonymous.
- `BaseTest` logs out in `@AfterMethod`, as `app`'s base class does.

**The API half keeps one token for the suite**, captured from the `Tokens` that `login()` returns and put on the
`Authorization` header by `authorized()`. This is the use the fixture documents for its return value — "callers
that need the raw tokens ... for `Authorization: Bearer` usage" — and unlike a browser session there is no cookie
state to manage. `app` logs in per request on its API tests because each asserts a different membership; here the
identity is constant, so a fresh flow per request would buy nothing but latency.

**Request state, not sessions, is what a chain has to clean up.** The shared `WebTest` accumulates headers, form
fields, and a body until something clears them, and `WebTestAsserter.reset()` clears cookies *as well*, which is
what a signed-in test cannot afford. So:

- `BaseTest.beforeMethod` calls `test.clearRequestState()`, so a method starts from nothing exactly as it starts
  with an empty database, and no chain ends in a `reset()` merely to protect whatever runs next.
- The two places that must clear state *mid-method* do exactly that and no more:
  `BaseTest.createOrganization` ends with `reset(ResetItem.Request)` because its form fields would otherwise ride
  along on the caller's next request, and the `briefing(...)` helpers in `BriefingAPITest` and
  `PipelineIntegrationTest` clear before building, because their headers would otherwise accumulate across the
  several calls one method makes.

Both safety nets are load-bearing, which was checked rather than assumed: removing the `@AfterMethod` logout fails
the two anonymous tests (an earlier method's session leaks into them), and removing `clearRequestState` fails
twelve.

Coverage of the API auth path, in `BriefingAPITest`:

| Test                             | Asserts                                                                        |
|----------------------------------|--------------------------------------------------------------------------------|
| `missingTokenIsUnauthorized`     | No `Authorization` header → `401` (kept, unchanged)                            |
| `unknownTokenIsUnauthorized`     | `Bearer nope` — unparseable as a JWT, and no refresh token → `401` (kept, unchanged) |
| `malformedBodyWithoutTokenIsUnauthorized` | New. Garbage body and no token → `401`, proving middleware precedes the body supplier |
| `expiredTokenWithARefreshTokenIsRefreshed` | New. An unverifiable access token plus `X-Refresh-Token` → `200`. The only coverage of the refresh path, where the Agency authenticates to FusionAuth with its client secret |
| every other test in the suite    | A real FusionAuth access token is accepted end to end                          |

`UserServiceTest` covers the claim mapping directly — every claim present, the optional ones absent, no `sub`, and
a `sub` that is not a UUID — with hand-built tokens and no server. The *wiring* of that translator into the profile
is covered by the API tests rather than separately: `oidc.user()` runs before the handler does any work, so a
translator that failed would fail the request. Verified by breaking `toUser` deliberately — 20 of the 113 tests
fail.

The client secret is not something a test has to pin separately: the fixture's own token exchange authenticates with
it, so a wrong value fails `@BeforeSuite` and skips the whole suite rather than passing quietly.

`AdminUIAuthenticationTest` covers the browser half:

| Test                                        | Asserts                                                                     |
|---------------------------------------------|------------------------------------------------------------------------------|
| `anAnonymousVisitorIsSentToLogin`           | `302` → `/login`, and an `oidc_return_to` cookie recording where they were headed |
| `everyAdminPathIsGated`                     | The same for four paths under the prefix, since a prefix-installed gate only means something if more than one route is checked |
| `loginRedirectsToTheProvider`               | `/login` hands off to FusionAuth with the **Agency's** client id, PKCE, and this port's callback |
| `theSignedInChromeNamesTheUserAndOffersSignOut` | A signed-in page renders the username, the email, and the sign-out form — the layout, the `viewer` parameter, and `UserService`'s claim mapping end to end |
| `logoutSendsTheBrowserToTheProvider`        | `POST /logout` answers the meta-refresh carrying the provider's logout URL |
| `logoutReturnClearsTheSessionCookies`       | `/oidc/logout-return` clears all three cookies with `Max-Age=0` and redirects to `/` |
| `aHandlerSessionDoesNotOpenTheAdminUI`      | The Handler's access **and** refresh tokens replayed as admin cookies → `302` → `/login` |
| `anAdminSessionDoesNotCallTheBriefingAPI`   | The admin's access token as an API bearer → `401`                            |

The last two are the audience boundary from decision 0, asserted in both directions.

What no test covers is the callback handler: `OIDCTestFixture` performs the token exchange itself rather than
walking through `/oidc/return`, so the suite proves the gate and the session endpoints but not the hop between
them. That hop was verified by hand against the development server — anonymous page → `/login` → provider login
form → consent → `/oidc/return` → cookies set → `/app/organizations/` renders at `200` — and again for logout,
following the provider's own meta-refresh back to `/oidc/logout-return`.

The tests need FusionAuth running, exactly as they already need PostgreSQL. `README.md` gets the same
"Local FusionAuth" section the Handler's README has.

## 11. Documentation changes

- `docs/design/2026-07-30-brief-pipeline-design.md` keeps its shape as the milestone-1 record, with §6, §10.1,
  §11, and decision 2 amended to point here rather than describing an authentication scheme that no longer exists.
- `README.md` gains the local FusionAuth instructions and the two-Application table.
- `src/main/resources/config.properties`'s comment block, which was mostly about guarding `handler.tokens`, is
  rewritten for the two client credentials and what keeps them apart.

## 12. Follow-ups this does not do

1. **Entitlements** — memberships, `entitled` from memberships, and the `403`. Milestone-1 design §10.4.
   `User` and `UserService` (§8) are the half of this that already exists; what is missing is somewhere to store
   the relationship between a `userId` and an Organization.
2. **A TLS listener**, after which the loopback bind can widen (§7). Session cookies are only marked `Secure` on
   an https request, so that is the gate on serving the admin UI to anything but the local machine.
3. **A post-logout destination that exists** — decision 10 points at `/` because `theagencyhq.dev` does not
   resolve. One line when it does.
4. **A shared FusionAuth for the Agency and the Handler in development.** Two instances (9015 and 9016) provisioning
   the same Handler Application id is a deliberate stopgap; the Handler's `authURL` can be pointed at 9016 to
   exercise the real end-to-end flow today.
5. **An error page.** `app` installs an `AppExceptionHandler` that renders one for browser routes, and the browser
   profile's `forbiddenHandler`/`unavailableHandler` currently fall back to Latte Web's bare-bones defaults.
