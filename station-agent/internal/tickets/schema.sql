CREATE TABLE IF NOT EXISTS cached_ticket (
    ticket_id TEXT PRIMARY KEY,
    ticket_number TEXT NOT NULL,
    trip_id TEXT NOT NULL,
    status TEXT NOT NULL,
    passenger_name TEXT NOT NULL DEFAULT '',
    seat_number INTEGER NOT NULL DEFAULT 0,
    cached_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cached_ticket_trip ON cached_ticket(trip_id);
CREATE INDEX IF NOT EXISTS idx_cached_ticket_number ON cached_ticket(ticket_number);
