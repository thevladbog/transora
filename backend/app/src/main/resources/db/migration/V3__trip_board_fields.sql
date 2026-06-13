CREATE TABLE IF NOT EXISTS scheduling.stations (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

ALTER TABLE scheduling.trips
    ADD COLUMN IF NOT EXISTS departure_station_code TEXT,
    ADD COLUMN IF NOT EXISTS expected_departure_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delay_minutes INTEGER;

UPDATE scheduling.trips
SET expected_departure_time = departure_time
WHERE expected_departure_time IS NULL;

UPDATE scheduling.trips
SET departure_station_code = 'T1'
WHERE departure_station_code IS NULL;

ALTER TABLE scheduling.trips
    ALTER COLUMN expected_departure_time SET NOT NULL,
    ALTER COLUMN departure_station_code SET NOT NULL;

INSERT INTO scheduling.stations (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'T1', 'Bus Station Terminal 1')
ON CONFLICT (code) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_trips_departure_station_code_time
    ON scheduling.trips (departure_station_code, expected_departure_time);
