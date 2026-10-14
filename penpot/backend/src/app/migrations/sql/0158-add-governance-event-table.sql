-- Proto Care: governance evidence.
--
-- `governance_event` is an append-only log of what happened with
-- governance: audits run, findings resolved, result status changes
-- (fixed, ignored, reopened), policy updates and design system
-- installs. It is the evidence trail behind the analytics (see
-- docs/03-analytics-plan.md).
-- `props` is plain JSON (not Transit) so it can be queried directly.
--
-- `governance_audit_result.area` stores the audit area of each finding
-- (design-system, accessibility, ui-quality, layer-semantics, usability)
-- so reports do not need to map rule categories, and `first_seen_at`
-- carries the time a finding was first detected across audits, so the
-- time to resolve it can be measured.

CREATE TABLE governance_event (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  type        text NOT NULL
              CHECK (type IN ('audit-run', 'finding-resolved', 'result-status',
                              'policy-updated', 'design-system-installed')),
  team_id     uuid NOT NULL REFERENCES team(id) ON DELETE CASCADE DEFERRABLE,
  project_id  uuid NULL REFERENCES project(id) ON DELETE CASCADE DEFERRABLE,
  file_id     uuid NULL REFERENCES file(id) ON DELETE CASCADE DEFERRABLE,
  audit_id    uuid NULL REFERENCES governance_audit(id) ON DELETE SET NULL DEFERRABLE,
  result_id   uuid NULL REFERENCES governance_audit_result(id) ON DELETE SET NULL DEFERRABLE,
  profile_id  uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
  rule_id     text NULL,
  area        text NULL,
  severity    text NULL,
  props       jsonb NOT NULL DEFAULT '{}'
);

CREATE INDEX governance_event__team_id__idx
    ON governance_event (team_id, created_at DESC);

CREATE INDEX governance_event__file_id__idx
    ON governance_event (file_id, created_at DESC);

CREATE INDEX governance_event__type__idx
    ON governance_event (team_id, type, created_at DESC);

ALTER TABLE governance_audit_result
  ADD COLUMN area text NULL,
  ADD COLUMN first_seen_at timestamptz NULL;
