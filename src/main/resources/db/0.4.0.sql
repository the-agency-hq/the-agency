-- Bitbucket Cloud joins GitHub and GitLab as a kind of Brief source. The row is shaped exactly as it was: a Bitbucket
-- source is a `type` of 'BITBUCKET', the repository's full name (`workspace/repository`) in `source`, and a
-- BitbucketConfig document in `source_config`. Only the CHECK that closes the set of kinds widens.
ALTER TABLE brief_sources DROP CONSTRAINT brief_sources_ck_type;
ALTER TABLE brief_sources ADD CONSTRAINT brief_sources_ck_type CHECK (type IN ('BITBUCKET', 'GITHUB', 'GITLAB'));
