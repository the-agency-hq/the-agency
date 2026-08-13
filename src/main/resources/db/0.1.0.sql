-- Initial PostgreSQL schema for The Agency.
--
-- Migrations in this directory are applied from the classpath by org.lattejava.database's Migrator when the app
-- starts (see DatabaseService). Files are named <semver>.sql and applied in SemVer order; each applied file is
-- recorded in the `versions` table with its SHA-256 checksum, so an applied migration must NEVER be edited -- add a
-- new, higher-versioned file instead. Timestamps are epoch-millis stored as BIGINT (mapped to java.time.Instant by
-- a jOOQ forced-type converter); enums are TEXT + CHECK (mapped to the Java enum by a forced-type converter).

-- The github_* columns are the GitHub credential an Organization's source is polled with. All nullable, because
-- "not connected" is an ordinary state: an Organization exists from the moment it is named and gains a credential
-- only when an operator authorizes the Agency's GitHub App for it. github_access_token IS NOT NULL is what
-- "connected" means. The expirations are epoch millis like every other instant column; github_login is the GitHub
-- account the credential belongs to, kept for the admin UI to display.
CREATE TABLE organizations (
  id                         UUID PRIMARY KEY,
  name                       TEXT   NOT NULL,
  github_login               TEXT,
  github_access_token        TEXT,
  github_access_expiration   BIGINT,
  github_refresh_token       TEXT,
  github_refresh_expiration  BIGINT,
  insert_instant             BIGINT NOT NULL,
  update_instant             BIGINT NOT NULL
);

-- Organization names are unique case-insensitively and first-come-first-serve, per idea.md.
CREATE UNIQUE INDEX organizations_uk_name ON organizations (LOWER(name));

-- Exactly one source per Organization (design decision 7): the GitHub repository its Briefs are built from.
CREATE TABLE brief_sources (
  id                   UUID PRIMARY KEY,
  organization_id      UUID   NOT NULL UNIQUE REFERENCES organizations (id) ON DELETE CASCADE,
  owner                TEXT   NOT NULL,
  repository           TEXT   NOT NULL,
  branch               TEXT   NOT NULL,
  last_built_commit    TEXT,
  last_polled_instant  BIGINT,
  last_status          TEXT   CHECK (last_status IN ('BUILD_FAILED', 'FETCH_FAILED', 'NOT_CONNECTED', 'OK', 'UNCHANGED')),
  last_error           TEXT,
  insert_instant       BIGINT NOT NULL,
  update_instant       BIGINT NOT NULL
);

-- One repository serves one Organization. Case-insensitive because GitHub owner and repository names are:
-- `Acme/Briefs` and `acme/briefs` are one repository, and a unique index over the raw text would happily register
-- both.
CREATE UNIQUE INDEX brief_sources_uk_repository ON brief_sources (LOWER(owner), LOWER(repository));

-- Insert-only version history. `document` is TEXT and not JSONB on purpose: JSONB reorders keys and normalizes
-- whitespace, so the document read back would not be the document written, and the Briefing API serves these
-- bytes verbatim.
CREATE TABLE briefs (
  id               UUID   PRIMARY KEY,
  organization_id  UUID   NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
  version          INT    NOT NULL,
  checksum         TEXT   NOT NULL,
  document         TEXT   NOT NULL,
  source_commit    TEXT,
  insert_instant   BIGINT NOT NULL,
  UNIQUE (organization_id, version)
);

CREATE INDEX briefs_idx_organization_version ON briefs (organization_id, version DESC);
