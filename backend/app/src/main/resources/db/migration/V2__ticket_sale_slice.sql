CREATE TABLE IF NOT EXISTS scheduling.trips (
    id UUID PRIMARY KEY,
    route_number TEXT NOT NULL,
    departure_station TEXT NOT NULL,
    arrival_station TEXT NOT NULL,
    departure_time TIMESTAMPTZ NOT NULL,
    platform TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS inventory.seats (
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    seat_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    PRIMARY KEY (trip_id, seat_number)
);

CREATE TABLE IF NOT EXISTS inventory.reservations (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    seat_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_reservations_trip_seat_active
    ON inventory.reservations (trip_id, seat_number)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS sales.tickets (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES inventory.reservations(id),
    shift_id UUID NOT NULL,
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id),
    seat_number INTEGER NOT NULL,
    passenger_name TEXT NOT NULL,
    price_cents BIGINT NOT NULL,
    status TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tickets_trip_id
    ON sales.tickets (trip_id);

CREATE TABLE IF NOT EXISTS sales.shifts (
    id UUID PRIMARY KEY,
    station_name TEXT NOT NULL,
    cashier_name TEXT NOT NULL,
    status TEXT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ
);

ALTER TABLE sales.tickets
    ADD CONSTRAINT fk_tickets_shift_id
    FOREIGN KEY (shift_id) REFERENCES sales.shifts(id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_shifts_cashier_open
    ON sales.shifts (cashier_name)
    WHERE status = 'OPEN';
