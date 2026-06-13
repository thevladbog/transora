ALTER TABLE inventory.seat_blocks
    ADD COLUMN IF NOT EXISTS blocked_by UUID,
    ADD COLUMN IF NOT EXISTS station_id UUID,
    ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS released_by UUID;

CREATE INDEX IF NOT EXISTS idx_seat_blocks_active
    ON inventory.seat_blocks (trip_id, seat_number)
    WHERE released_at IS NULL;

ALTER TABLE inventory.sales_restrictions
    ADD COLUMN IF NOT EXISTS schedule_entry_id UUID REFERENCES scheduling.schedule_entries(id);

CREATE INDEX IF NOT EXISTS idx_sales_restrictions_entry
    ON inventory.sales_restrictions (schedule_entry_id, station_id)
    WHERE status = 'ACTIVE' AND schedule_entry_id IS NOT NULL;
