-- Initial PostgreSQL schema for The Agency.
--
-- Migrations in this directory are applied from the classpath by org.lattejava.database's Migrator when the app
-- starts (see DatabaseService). Files are named <semver>.sql and applied in SemVer order; each applied file is
-- recorded in the `versions` table with its SHA-256 checksum, so an applied migration must NEVER be edited -- add a
-- new, higher-versioned file instead. Timestamps are epoch-millis stored as BIGINT (mapped to java.time.Instant by
-- a jOOQ forced-type converter); enums are TEXT + CHECK (mapped to the Java enum by a forced-type converter).

CREATE TABLE organizations (
  id              UUID PRIMARY KEY,
  name            TEXT   NOT NULL,
  insert_instant  BIGINT NOT NULL,
  update_instant  BIGINT NOT NULL
);

-- Organization names are unique case-insensitively and first-come-first-serve, per idea.md.
CREATE UNIQUE INDEX organizations_uk_name ON organizations (LOWER(name));

-- Exactly one source per Organization (design decision 7).
CREATE TABLE brief_sources (
  id                   UUID PRIMARY KEY,
  organization_id      UUID   NOT NULL UNIQUE REFERENCES organizations (id) ON DELETE CASCADE,
  path                 TEXT   NOT NULL UNIQUE,
  last_built_commit    TEXT,
  last_polled_instant  BIGINT,
  last_status          TEXT   CHECK (last_status IN ('BUILD_FAILED', 'NOT_A_REPOSITORY', 'OK', 'UNCHANGED')),
  last_error           TEXT,
  last_pull_error      TEXT,
  insert_instant       BIGINT NOT NULL,
  update_instant       BIGINT NOT NULL
);

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
