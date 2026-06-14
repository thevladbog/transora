ALTER TABLE scheduling.routes
    ADD COLUMN IF NOT EXISTS route_number TEXT;

UPDATE scheduling.routes SET route_number = code WHERE route_number IS NULL AND code IS NOT NULL;
UPDATE scheduling.routes SET route_number = name WHERE route_number IS NULL;

ALTER TABLE scheduling.routes
    ALTER COLUMN route_number SET NOT NULL;

ALTER TABLE scheduling.route_stops
    ADD COLUMN IF NOT EXISTS point_id UUID REFERENCES scheduling.points(id);

CREATE INDEX IF NOT EXISTS idx_route_stops_point ON scheduling.route_stops (point_id);
