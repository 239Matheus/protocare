-- Proto Care: micro view and design system themes.
--
-- `governance_audit.themes` keeps the token themes active in the file
-- when it was audited (e.g. "Mode/Light"), so reports can be filtered by
-- design system theme.
--
-- `governance_audit_result.board_id/board_name` is the top-level board
-- (screen) of each finding, so reports can go from a file to its screens
-- and from a screen to its findings.

ALTER TABLE governance_audit
  ADD COLUMN themes text[] NOT NULL DEFAULT '{}';

CREATE INDEX governance_audit__themes__idx
    ON governance_audit USING gin (themes);

ALTER TABLE governance_audit_result
  ADD COLUMN board_id uuid NULL,
  ADD COLUMN board_name text NULL;
