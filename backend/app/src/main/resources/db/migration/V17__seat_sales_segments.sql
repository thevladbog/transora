CREATE TABLE IF NOT EXISTS inventory.seat_sales (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    seat_number INTEGER NOT NULL,
    from_stop_order SMALLINT NOT NULL,
    to_stop_order SMALLINT NOT NULL,
    ticket_id UUID REFERENCES sales.tickets(id),
    reservation_id UUID REFERENCES inventory.reservations(id),
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_seat_sale_stops CHECK (to_stop_order > from_stop_order)
);

CREATE INDEX IF NOT EXISTS idx_seat_sales_trip_seat
    ON inventory.seat_sales (trip_id, seat_number);

CREATE INDEX IF NOT EXISTS idx_seat_sales_active
    ON inventory.seat_sales (trip_id, seat_number, from_stop_order, to_stop_order)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_seat_sales_ticket
    ON inventory.seat_sales (ticket_id)
    WHERE ticket_id IS NOT NULL;
