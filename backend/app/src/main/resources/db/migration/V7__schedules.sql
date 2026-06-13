CREATE TABLE IF NOT EXISTS scheduling.schedules (
    id             UUID PRIMARY KEY,
    route_id       UUID NOT NULL REFERENCES scheduling.routes(id),
    name           TEXT NOT NULL,
    schedule_type  TEXT NOT NULL CHECK (schedule_type IN ('PERMANENT', 'SEASONAL', 'EXCEPTION')),
    valid_from     DATE,
    valid_to       DATE,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_by     UUID NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_seasonal_dates CHECK (
        schedule_type = 'PERMANENT'
        OR (valid_from IS NOT NULL AND valid_to IS NOT NULL)
    ),
    CONSTRAINT chk_exception_single_date CHECK (
        schedule_type != 'EXCEPTION'
        OR valid_from = valid_to
    ),
    CONSTRAINT chk_date_order CHECK (
        valid_from IS NULL OR valid_to IS NULL
        OR valid_to >= valid_from
    )
);

CREATE INDEX IF NOT EXISTS idx_schedules_route ON scheduling.schedules(route_id);
CREATE INDEX IF NOT EXISTS idx_schedules_type ON scheduling.schedules(schedule_type);
CREATE INDEX IF NOT EXISTS idx_schedules_active ON scheduling.schedules(is_active);

CREATE TABLE IF NOT EXISTS scheduling.schedule_entries (
    id                 UUID PRIMARY KEY,
    schedule_id        UUID NOT NULL REFERENCES scheduling.schedules(id) ON DELETE CASCADE,
    trip_number        TEXT NOT NULL,
    departure_time     TIME NOT NULL,
    days_of_week       SMALLINT[] NOT NULL,
    default_vehicle_id UUID REFERENCES scheduling.vehicles(id),
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_schedule_entry_number UNIQUE (schedule_id, trip_number),
    CONSTRAINT chk_days_of_week CHECK (
        array_length(days_of_week, 1) > 0
        AND days_of_week <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::SMALLINT[]
    )
);

CREATE INDEX IF NOT EXISTS idx_schedule_entries_schedule ON scheduling.schedule_entries(schedule_id);

ALTER TABLE scheduling.trips
    ADD COLUMN IF NOT EXISTS schedule_entry_id UUID REFERENCES scheduling.schedule_entries(id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_trips_schedule_entry_date
    ON scheduling.trips (schedule_entry_id, trip_date)
    WHERE schedule_entry_id IS NOT NULL AND trip_date IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_trips_trip_date ON scheduling.trips(trip_date);
