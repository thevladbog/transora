CREATE TABLE IF NOT EXISTS boarding_buffer (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client_event_id TEXT NOT NULL UNIQUE,
    ticket_id TEXT NOT NULL,
    trip_id TEXT NOT NULL,
    scanned_by TEXT NOT NULL,
    scanned_at TEXT NOT NULL,
    result TEXT NOT NULL DEFAULT 'BUFFERED',
    synced INTEGER NOT NULL DEFAULT 0,
    sync_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_buffer_synced ON boarding_buffer(synced);
