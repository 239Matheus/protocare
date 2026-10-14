-- Proto Care: governance tables.
--
-- `governance_policy` holds, per team, which rules run, with which
-- severity and options. One row per team; absent row means defaults.
--
-- `governance_role` adds the product roles on top of the team roles:
-- `designer` (default for any member), `design-ops` (reads aggregated
-- health) and `ds-admin` (manages the policy). Team owners/admins can
-- always manage the policy.
--
-- `governance_audit` is one run of the Governance Engine over a file at
-- a given revision. `summary` and `stats` are the engine outputs.
--
-- `governance_audit_result` is one finding of one run. `payload` keeps
-- the whole engine result (message, recommendation, values, fix) and
-- `status` tracks what happened to it afterwards.

CREATE TABLE governance_policy (
  team_id     uuid PRIMARY KEY REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),
  rules       jsonb NOT NULL DEFAULT '{}',
  options     jsonb NOT NULL DEFAULT '{}'
);

CREATE TABLE governance_role (
  team_id     uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  profile_id  uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE DEFERRABLE,
  role        text NOT NULL CHECK (role IN ('designer', 'design-ops', 'ds-admin')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (team_id, profile_id)
);

CREATE TABLE governance_audit (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  file_id        uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  project_id     uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE DEFERRABLE,
  team_id        uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  profile_id     uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  file_revn      bigint NOT NULL DEFAULT 0,
  file_vern      bigint NOT NULL DEFAULT 0,
  page_id        uuid NULL,
  engine_version text NOT NULL,
  summary        jsonb NOT NULL,
  stats          jsonb NOT NULL DEFAULT '{}'
);

CREATE INDEX governance_audit__file_id__idx
    ON governance_audit (file_id, created_at DESC);

CREATE INDEX governance_audit__project_id__idx
    ON governance_audit (project_id, created_at DESC);

CREATE INDEX governance_audit__team_id__idx
    ON governance_audit (team_id, created_at DESC);

CREATE TABLE governance_audit_result (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  audit_id      uuid NOT NULL REFERENCES governance_audit(id) ON DELETE CASCADE DEFERRABLE,
  rule_id       text NOT NULL,
  category      text NOT NULL,
  severity      text NOT NULL,
  page_id       uuid NOT NULL,
  node_id       uuid NOT NULL,
  node_name     text NULL,
  attribute     text NULL,
  fix_available boolean NOT NULL DEFAULT false,
  status        text NOT NULL DEFAULT 'open'
                CHECK (status IN ('open', 'fixed', 'ignored')),
  payload       jsonb NOT NULL
);

CREATE INDEX governance_audit_result__audit_id__idx
    ON governance_audit_result (audit_id);
