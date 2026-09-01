-- The Agents an Organization is interested in, as a JSON document: {"enabled": ["CLAUDE", "CURSOR"]}. NULL means
-- every Agent, which is the default and what every existing Organization gets. The set of Agents lives in Java
-- (the Agent enum), so there is no table to reference.
ALTER TABLE organizations ADD COLUMN agents JSONB;
