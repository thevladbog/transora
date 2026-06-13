CREATE TABLE IF NOT EXISTS scheduling.service_stations (
    id         UUID PRIMARY KEY,
    code       TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    city       TEXT NOT NULL,
    timezone   TEXT NOT NULL DEFAULT 'Europe/Moscow',
    address    TEXT,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scheduling.carriers (
    id             UUID PRIMARY KEY,
    name           TEXT NOT NULL,
    legal_name     TEXT NOT NULL,
    inn            TEXT NOT NULL UNIQUE,
    contract_type  TEXT NOT NULL CHECK (contract_type IN ('ROUTE_RENT', 'SERVICE_FEE')),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scheduling.seat_layouts (
    id           UUID PRIMARY KEY,
    name         TEXT NOT NULL,
    total_seats  SMALLINT NOT NULL CHECK (total_seats > 0),
    layout_json  JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scheduling.routes (
    id          UUID PRIMARY KEY,
    carrier_id  UUID NOT NULL REFERENCES scheduling.carriers(id),
    name        TEXT NOT NULL,
    code        TEXT UNIQUE,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_routes_carrier ON scheduling.routes(carrier_id);

CREATE TABLE IF NOT EXISTS scheduling.route_stops (
    id                     UUID PRIMARY KEY,
    route_id               UUID NOT NULL REFERENCES scheduling.routes(id) ON DELETE CASCADE,
    stop_order             SMALLINT NOT NULL,
    stop_name              TEXT NOT NULL,
    station_id             UUID REFERENCES scheduling.service_stations(id),
    is_external            BOOLEAN NOT NULL DEFAULT FALSE,
    scheduled_duration_min INTEGER CHECK (scheduled_duration_min IS NULL OR scheduled_duration_min > 0),
    dwell_time_min         INTEGER NOT NULL DEFAULT 5 CHECK (dwell_time_min >= 0),
    CONSTRAINT uq_route_stop_order UNIQUE (route_id, stop_order),
    CONSTRAINT chk_external_no_station CHECK (NOT (is_external = FALSE AND station_id IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_route_stops_route ON scheduling.route_stops(route_id);

CREATE TABLE IF NOT EXISTS scheduling.vehicles (
    id              UUID PRIMARY KEY,
    carrier_id      UUID NOT NULL REFERENCES scheduling.carriers(id),
    model           TEXT NOT NULL,
    plate_number    TEXT NOT NULL UNIQUE,
    seat_layout_id  UUID NOT NULL REFERENCES scheduling.seat_layouts(id),
    total_seats     SMALLINT NOT NULL,
    year            SMALLINT,
    notes           TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_vehicles_carrier ON scheduling.vehicles(carrier_id);

CREATE TABLE IF NOT EXISTS scheduling.drivers (
    id          UUID PRIMARY KEY,
    carrier_id  UUID NOT NULL REFERENCES scheduling.carriers(id),
    full_name   TEXT NOT NULL,
    license_no  TEXT NOT NULL,
    phone       TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_drivers_carrier ON scheduling.drivers(carrier_id);

ALTER TABLE scheduling.trips
    ADD COLUMN IF NOT EXISTS route_id UUID REFERENCES scheduling.routes(id),
    ADD COLUMN IF NOT EXISTS vehicle_id UUID REFERENCES scheduling.vehicles(id),
    ADD COLUMN IF NOT EXISTS driver_id UUID REFERENCES scheduling.drivers(id),
    ADD COLUMN IF NOT EXISTS trip_date DATE,
    ADD COLUMN IF NOT EXISTS auto_generated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS scheduling.trip_stops (
    id                   UUID PRIMARY KEY,
    trip_id              UUID NOT NULL REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    route_stop_id        UUID NOT NULL REFERENCES scheduling.route_stops(id),
    stop_order           SMALLINT NOT NULL,
    stop_name            TEXT NOT NULL,
    station_id           UUID REFERENCES scheduling.service_stations(id),
    is_external          BOOLEAN NOT NULL DEFAULT FALSE,
    scheduled_arrival    TIMESTAMPTZ,
    scheduled_departure  TIMESTAMPTZ NOT NULL,
    estimated_arrival    TIMESTAMPTZ,
    estimated_departure  TIMESTAMPTZ,
    actual_arrival       TIMESTAMPTZ,
    actual_departure     TIMESTAMPTZ,
    stop_status          TEXT NOT NULL DEFAULT 'UPCOMING',
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_trip_stop_order UNIQUE (trip_id, stop_order)
);

CREATE INDEX IF NOT EXISTS idx_trip_stops_trip ON scheduling.trip_stops(trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_stops_station ON scheduling.trip_stops(station_id);

INSERT INTO scheduling.service_stations (id, code, name, city, timezone)
VALUES ('00000000-0000-0000-0000-000000000001', 'T1', 'Bus Station Terminal 1', 'Transora', 'Europe/Moscow')
ON CONFLICT (code) DO NOTHING;
