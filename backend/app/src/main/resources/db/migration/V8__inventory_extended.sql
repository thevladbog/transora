CREATE TABLE IF NOT EXISTS inventory.trip_inventory (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL UNIQUE REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    total_seats INTEGER NOT NULL CHECK (total_seats > 0),
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trip_inventory_trip_id
    ON inventory.trip_inventory (trip_id);

CREATE TABLE IF NOT EXISTS inventory.sales_restrictions (
    id UUID PRIMARY KEY,
    trip_id UUID REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    station_id UUID,
    allowed_seats INTEGER[] NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    scope TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sales_restrictions_trip
    ON inventory.sales_restrictions (trip_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS inventory.seat_blocks (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    seat_number INTEGER NOT NULL,
    block_type TEXT NOT NULL DEFAULT 'MANUAL',
    reason TEXT,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_seat_blocks_trip_seat
    ON inventory.seat_blocks (trip_id, seat_number);

CREATE TABLE IF NOT EXISTS inventory.transit_gates (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    station_id UUID NOT NULL,
    stop_order SMALLINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'AWAITING_ARRIVAL',
    available_seats INTEGER[],
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_transit_gate_trip_station UNIQUE (trip_id, station_id)
);

CREATE INDEX IF NOT EXISTS idx_transit_gates_trip
    ON inventory.transit_gates (trip_id);

ALTER TABLE inventory.reservations
    ADD COLUMN IF NOT EXISTS session_id TEXT,
    ADD COLUMN IF NOT EXISTS from_stop_order SMALLINT,
    ADD COLUMN IF NOT EXISTS to_stop_order SMALLINT,
    ADD COLUMN IF NOT EXISTS inventory_id UUID REFERENCES inventory.trip_inventory(id);

CREATE INDEX IF NOT EXISTS idx_reservations_session_active
    ON inventory.reservations (session_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_reservations_expires_active
    ON inventory.reservations (expires_at)
    WHERE status = 'ACTIVE';
