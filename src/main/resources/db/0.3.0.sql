-- Brief sources become typed. A source row carries its kind in `type`, the identity that kind is unique by in
-- `source` (for GitHub, the repository as `owner/repository`), and the whole of the kind's configuration -- the
-- credential included -- as one JSON document in `source_config`. The GitHub columns that lived on `organizations`
-- move into that document, so an Organization's credential now has the lifetime of its source rather than of the
-- Organization, and a second kind of source is a new `type` value rather than a new set of columns.
ALTER TABLE brief_sources ADD COLUMN type TEXT;
ALTER TABLE brief_sources ADD COLUMN source TEXT;
ALTER TABLE brief_sources ADD COLUMN source_config JSONB;

-- Every existing source is a GitHub repository, and the credential that polls it is on its Organization's row.
-- The document is built to exactly the shape the Java codec writes: the discriminator, the nested connection
-- (absent when the Organization holds no credential), and the repository. The expirations stay epoch millis, as
-- the columns were. jsonb_strip_nulls drops every absent member, at every depth, matching the codec's omitNulls.
UPDATE brief_sources s
SET type = 'GITHUB',
    source = s.owner || '/' || s.repository,
    source_config = jsonb_strip_nulls(jsonb_build_object(
      'type', 'GITHUB',
      'connection', CASE
        WHEN o.github_access_token IS NULL THEN NULL
        ELSE jsonb_build_object(
          'login', o.github_login,
          'tokens', jsonb_build_object(
            'accessToken', o.github_access_token,
            'accessExpiration', o.github_access_expiration,
            'refreshToken', o.github_refresh_token,
            'refreshTokenExpiration', o.github_refresh_expiration))
      END,
      'owner', s.owner,
      'repository', s.repository,
      'branch', s.branch))
FROM organizations o
WHERE o.id = s.organization_id;

-- The repository columns have moved into the document, and they were NOT NULL: they have to go before a source
-- with no repository can be inserted below. The old index goes with them.
DROP INDEX brief_sources_uk_repository;
ALTER TABLE brief_sources DROP COLUMN owner;
ALTER TABLE brief_sources DROP COLUMN repository;
ALTER TABLE brief_sources DROP COLUMN branch;

-- An Organization that authorized GitHub but never picked a repository has a credential and no source row. It
-- becomes a GitHub source with a connection and no repository, which is the state the OAuth callback now creates
-- directly. The instants are the Organization's own, since its last update was the credential write.
INSERT INTO brief_sources (id, organization_id, type, source, source_config, insert_instant, update_instant)
SELECT gen_random_uuid(),
       o.id,
       'GITHUB',
       NULL,
       jsonb_build_object(
         'type', 'GITHUB',
         'connection', jsonb_strip_nulls(jsonb_build_object(
           'login', o.github_login,
           'tokens', jsonb_build_object(
             'accessToken', o.github_access_token,
             'accessExpiration', o.github_access_expiration,
             'refreshToken', o.github_refresh_token,
             'refreshTokenExpiration', o.github_refresh_expiration)))),
       o.update_instant,
       o.update_instant
FROM organizations o
WHERE o.github_access_token IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM brief_sources s WHERE s.organization_id = o.id);

ALTER TABLE brief_sources ALTER COLUMN type SET NOT NULL;
ALTER TABLE brief_sources ALTER COLUMN source_config SET NOT NULL;
ALTER TABLE brief_sources ADD CONSTRAINT brief_sources_ck_type CHECK (type IN ('GITHUB'));
-- The column and the document both name the kind: the column for SQL, the document for the codec. They must agree.
ALTER TABLE brief_sources ADD CONSTRAINT brief_sources_ck_source_config_type CHECK (source_config->>'type' = type);

-- One source identity serves one Organization, per kind. Case-insensitive because GitHub repository names are, and
-- a row whose kind has not been told what to poll yet has a NULL source, which the index does not count.
CREATE UNIQUE INDEX brief_sources_uk_source ON brief_sources (type, LOWER(source));

ALTER TABLE organizations DROP COLUMN github_login;
ALTER TABLE organizations DROP COLUMN github_access_token;
ALTER TABLE organizations DROP COLUMN github_access_expiration;
ALTER TABLE organizations DROP COLUMN github_refresh_token;
ALTER TABLE organizations DROP COLUMN github_refresh_expiration;

-- GitLab joins GitHub as a kind of Brief source. The row is shaped exactly as it was: a GitLab source is a `type`
-- of 'GITLAB', its project's path with its namespace in `source`, and a GitLabConfig document in `source_config`.
-- Only the CHECK that closes the set of kinds widens.
ALTER TABLE brief_sources DROP CONSTRAINT brief_sources_ck_type;
ALTER TABLE brief_sources ADD CONSTRAINT brief_sources_ck_type CHECK (type IN ('GITHUB', 'GITLAB'));

