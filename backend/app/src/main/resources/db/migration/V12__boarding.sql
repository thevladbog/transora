CREATE SCHEMA IF NOT EXISTS boarding;

CREATE TABLE IF NOT EXISTS boarding.scan_events (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES sales.tickets(id),
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id),
    station_id UUID NOT NULL,
    scanned_by UUID NOT NULL,
    scan_result TEXT NOT NULL,
    scanned_at TIMESTAMPTZ NOT NULL,
    client_event_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_boarding_client_event UNIQUE (client_event_id)
);

CREATE INDEX IF NOT EXISTS idx_boarding_scan_trip
    ON boarding.scan_events (trip_id, scanned_at);

CREATE INDEX IF NOT EXISTS idx_boarding_scan_ticket
    ON boarding.scan_events (ticket_id);

CREATE TABLE IF NOT EXISTS boarding.sync_batches (
    id UUID PRIMARY KEY,
    station_id UUID NOT NULL,
    synced_by UUID NOT NULL,
    event_count INTEGER NOT NULL,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
