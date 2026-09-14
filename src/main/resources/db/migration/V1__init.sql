-- Greenfield schema (DEC-0002, DEC-0008). This is first-time setup, not a
-- migration against an existing shape -- the orchestration graph's own
-- apply_migration node is SKIPPED for exactly this reason (design_doc.
-- requires_schema_change == false for greenfield). V2 (brownfield click
-- analytics) is the first real migration; see V2__click_analytics.sql.
CREATE TABLE links (
  code        TEXT PRIMARY KEY,
  target_url  TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  expires_at  TEXT,
  status      TEXT NOT NULL DEFAULT 'active',
  click_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_links_status ON links(status);

-- Idempotent create, keyed by the caller-supplied Idempotency-Key.
CREATE TABLE idempotency_keys (
  idempotency_key TEXT PRIMARY KEY,
  code            TEXT NOT NULL,
  created_at      TEXT NOT NULL
);
