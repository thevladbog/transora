ALTER TABLE inventory.transit_gates
    ADD COLUMN IF NOT EXISTS opened_by UUID,
    ADD COLUMN IF NOT EXISTS opened_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS closed_by UUID,
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

UPDATE inventory.transit_gates
SET updated_at = created_at
WHERE updated_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_transit_gates_station_status
    ON inventory.transit_gates (station_id, status);
