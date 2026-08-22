/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
import { defineRailway, github, postgres, preserve, project, service } from "railway/iac";

// The Railway deployment, per docs/design/2026-08-18-railway-deploy-design.md §8.
// Literals are facts fixed by the design. Secrets are declared preserve() — the value lives only in
// Railway, entered once in the dashboard on the owning service (design §10.5); apply keeps a declared
// variable's existing value and never writes one from this file. The Agency reads FusionAuth's secrets
// through typed references, so each secret has exactly one home. Do NOT use project shared variables:
// the DSL cannot declare them, and apply deletes what the file does not declare. Composed JDBC URLs use
// Railway's `${{...}}` template syntax because a typed reference cannot be embedded in a larger string.
// Every resource is pinned to US East (Virginia); the other US region is us-west2 (California).
const REGION = "us-east4-eqdc4a";

export default defineRailway(() => {
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
    // First deploy only: Railway rejects custom-domain *registration* from configuration. Keep this
    // commented until the domain is registered in the dashboard (design §10.4), then restore it.
    // domains: [{ domain: "auth.theagencyhq.dev", port: 9011 }],
    env: {
      DATABASE_PASSWORD: preserve(),
      DATABASE_ROOT_PASSWORD: fusionauthPostgres.env.PGPASSWORD,
      DATABASE_ROOT_USERNAME: fusionauthPostgres.env.PGUSER,
      DATABASE_URL: "jdbc:postgresql://${{fusionauth-postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/fusionauth",
      DATABASE_USERNAME: "fusionauth",
      FUSIONAUTH_APP_KICKSTART_FILE: "/usr/local/fusionauth/kickstart/kickstart.json",
      FUSIONAUTH_APP_LICENSE_KEY: preserve(),
      FUSIONAUTH_APP_MEMORY: "512M",
      FUSIONAUTH_APP_RUNTIME_MODE: "production",
      FUSIONAUTH_APP_THEME_APP_URL: "https://app.theagencyhq.dev",
      FUSIONAUTH_APP_THEME_CSS_URL: "https://app.theagencyhq.dev/static/css/app.css",
      FUSIONAUTH_APP_URL: "https://auth.theagencyhq.dev",
      KICKSTART_ADMIN_PASSWORD: preserve(),
      KICKSTART_AGENCY_CLIENT_SECRET: preserve(),
      KICKSTART_API_KEY: preserve(),
      KICKSTART_HANDLER_CLIENT_SECRET: preserve(),
      KICKSTART_ORDINARY_PASSWORD: preserve(),
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
    // First deploy only: Railway rejects custom-domain *registration* from configuration. Keep this
    // commented until the domain is registered in the dashboard (design §10.4), then restore it.
    // domains: [{ domain: "app.theagencyhq.dev", port: 8080 }],
    env: {
      DB_PASSWORD: agencyPostgres.env.PGPASSWORD,
      DB_URL: "jdbc:postgresql://${{agency-postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/${{agency-postgres.PGDATABASE}}",
      DB_USERNAME: agencyPostgres.env.PGUSER,
      FUSIONAUTH_APIKEY: fusionauth.env.KICKSTART_API_KEY,
      FUSIONAUTH_BASEURL: "https://auth.theagencyhq.dev",
      FUSIONAUTH_CLIENTID: "7e1c9a54-0f8b-4a2e-9c6d-3b5f81d0a742",
      FUSIONAUTH_CLIENTSECRET: fusionauth.env.KICKSTART_AGENCY_CLIENT_SECRET,
      FUSIONAUTH_HANDLERCLIENTID: "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73",
      FUSIONAUTH_HANDLERCLIENTSECRET: fusionauth.env.KICKSTART_HANDLER_CLIENT_SECRET,
      FUSIONAUTH_ISSUER: "https://auth.theagencyhq.dev",
      GITHUB_APPNAME: preserve(),
      GITHUB_CLIENTID: preserve(),
      GITHUB_CLIENTSECRET: preserve(),
      RUNTIME_MODE: "production",
      WEB_COOKIEENCRYPTIONKEY: preserve(),
    },
    healthcheck: "/health",
    replicas: 1,
  });

  return project("The Agency HQ", {
    resources: [agencyPostgres, fusionauthPostgres, fusionauth, theAgency],
  });
});
