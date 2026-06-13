ALTER TABLE scheduling.trips
    ADD COLUMN IF NOT EXISTS trip_number TEXT;

UPDATE scheduling.trips
SET trip_number = route_number
WHERE trip_number IS NULL;

ALTER TABLE scheduling.trip_stops
    ADD COLUMN IF NOT EXISTS updated_by UUID;

CREATE TABLE IF NOT EXISTS scheduling.trip_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id     UUID NOT NULL REFERENCES scheduling.trips(id),
    event_type  TEXT NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    changed_by  UUID,
    station_id  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trip_audit_trip ON scheduling.trip_audit_log(trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_audit_created ON scheduling.trip_audit_log(created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_trips_route_date_number
    ON scheduling.trips (route_id, trip_date, trip_number)
    WHERE route_id IS NOT NULL AND trip_date IS NOT NULL AND trip_number IS NOT NULL
      AND status != 'CANCELLED';
