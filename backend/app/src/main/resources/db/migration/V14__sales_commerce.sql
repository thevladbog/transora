CREATE TABLE IF NOT EXISTS sales.tariffs (
    id UUID PRIMARY KEY,
    route_number TEXT NOT NULL,
    from_stop_order SMALLINT NOT NULL DEFAULT 1,
    to_stop_order SMALLINT NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tariff_stops CHECK (to_stop_order > from_stop_order)
);

CREATE INDEX IF NOT EXISTS idx_tariffs_route_active
    ON sales.tariffs (route_number, is_active);

CREATE TABLE IF NOT EXISTS sales.refund_policies (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    service_fee_cents BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sales.refund_policy_tiers (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES sales.refund_policies(id) ON DELETE CASCADE,
    hours_before_min INTEGER,
    hours_before_max INTEGER,
    penalty_percent NUMERIC(5, 2) NOT NULL DEFAULT 0
        CHECK (penalty_percent BETWEEN 0 AND 100),
    refund_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order SMALLINT NOT NULL,
    CONSTRAINT uq_refund_tier_policy_order UNIQUE (policy_id, sort_order)
);

CREATE TABLE IF NOT EXISTS sales.orders (
    id UUID PRIMARY KEY,
    shift_id UUID NOT NULL REFERENCES sales.shifts(id),
    status TEXT NOT NULL DEFAULT 'PENDING',
    total_cents BIGINT NOT NULL CHECK (total_cents >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_orders_shift
    ON sales.orders (shift_id);

CREATE INDEX IF NOT EXISTS idx_orders_status_expires
    ON sales.orders (expires_at)
    WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS sales.order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES sales.orders(id) ON DELETE CASCADE,
    reservation_id UUID NOT NULL REFERENCES inventory.reservations(id),
    trip_id UUID NOT NULL REFERENCES scheduling.trips(id),
    seat_number INTEGER NOT NULL,
    passenger_name TEXT NOT NULL,
    doc_type TEXT NOT NULL,
    doc_number TEXT NOT NULL,
    from_stop_order SMALLINT NOT NULL,
    to_stop_order SMALLINT NOT NULL,
    tariff_id UUID REFERENCES sales.tariffs(id),
    price_cents BIGINT NOT NULL CHECK (price_cents >= 0)
);

CREATE INDEX IF NOT EXISTS idx_order_items_order
    ON sales.order_items (order_id);

CREATE TABLE IF NOT EXISTS sales.payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES sales.orders(id) ON DELETE CASCADE,
    payment_type TEXT NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    status TEXT NOT NULL DEFAULT 'PENDING',
    transaction_id TEXT,
    processed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS sales.refunds (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL UNIQUE REFERENCES sales.tickets(id),
    policy_id UUID NOT NULL REFERENCES sales.refund_policies(id),
    penalty_percent NUMERIC(5, 2) NOT NULL,
    penalty_cents BIGINT NOT NULL DEFAULT 0,
    service_fee_cents BIGINT NOT NULL DEFAULT 0,
    refund_cents BIGINT NOT NULL CHECK (refund_cents >= 0),
    refund_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE sales.tickets
    ADD COLUMN IF NOT EXISTS order_id UUID REFERENCES sales.orders(id),
    ADD COLUMN IF NOT EXISTS doc_type TEXT,
    ADD COLUMN IF NOT EXISTS doc_number TEXT;

INSERT INTO sales.refund_policies (id, name, is_active, service_fee_cents, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    'PP RF 112 default',
    TRUE,
    0,
    NOW()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sales.refund_policy_tiers (id, policy_id, hours_before_min, hours_before_max, penalty_percent, refund_allowed, sort_order)
VALUES
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000101', 24, NULL, 0, TRUE, 1),
    ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000101', 2, 24, 10, TRUE, 2),
    ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000101', 0, 2, 25, TRUE, 3),
    ('00000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000101', NULL, 0, 100, FALSE, 4)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sales.tariffs (id, route_number, from_stop_order, to_stop_order, price_cents, is_active, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000301', '101', 1, 2, 125000, TRUE, NOW()),
    ('00000000-0000-0000-0000-000000000302', '202', 1, 2, 98000, TRUE, NOW()),
    ('00000000-0000-0000-0000-000000000303', 'DEFAULT', 1, 2, 100000, TRUE, NOW())
ON CONFLICT (id) DO NOTHING;
