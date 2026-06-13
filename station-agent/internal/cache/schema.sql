CREATE TABLE IF NOT EXISTS cached_trip (
    trip_id TEXT PRIMARY KEY,
    trip_number TEXT NOT NULL,
    trip_date TEXT NOT NULL,
    route_name TEXT NOT NULL,
    status TEXT NOT NULL,
    delay_minutes INTEGER NOT NULL DEFAULT 0,
    stops_json TEXT NOT NULL DEFAULT '[]',
    platform_number INTEGER,
    is_departure INTEGER NOT NULL DEFAULT 1,
    display_time TEXT NOT NULL,
    direction_stop TEXT NOT NULL,
    cached_at INTEGER NOT NULL,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cached_trip_date ON cached_trip(trip_date, display_time);
CREATE INDEX IF NOT EXISTS idx_cached_trip_status ON cached_trip(status);

CREATE TABLE IF NOT EXISTS cache_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
