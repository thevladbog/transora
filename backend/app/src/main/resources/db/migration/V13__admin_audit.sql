CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE IF NOT EXISTS admin.audit_log (
    id UUID PRIMARY KEY,
    actor_id UUID,
    station_id UUID,
    module TEXT NOT NULL,
    action TEXT NOT NULL,
    entity_type TEXT,
    entity_id TEXT,
    details_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created
    ON admin.audit_log (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_station
    ON admin.audit_log (station_id, created_at DESC)
    WHERE station_id IS NOT NULL;
