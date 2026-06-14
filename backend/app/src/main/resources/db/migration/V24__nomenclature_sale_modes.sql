ALTER TABLE sales.nomenclature_items
    ADD COLUMN IF NOT EXISTS sale_mode TEXT NOT NULL DEFAULT 'STANDALONE'
        CHECK (sale_mode IN ('STANDALONE', 'TICKET_ATTACHED')),
    ADD COLUMN IF NOT EXISTS pricing_mode TEXT NOT NULL DEFAULT 'FIXED'
        CHECK (pricing_mode IN ('FIXED', 'PERCENT_OF_ROUTE')),
    ADD COLUMN IF NOT EXISTS route_percent NUMERIC(5, 2)
        CHECK (route_percent IS NULL OR (route_percent >= 0 AND route_percent <= 100)),
    ADD COLUMN IF NOT EXISTS min_price_cents BIGINT
        CHECK (min_price_cents IS NULL OR min_price_cents >= 0),
    ADD COLUMN IF NOT EXISTS max_price_cents BIGINT
        CHECK (max_price_cents IS NULL OR max_price_cents >= 0),
    ADD COLUMN IF NOT EXISTS max_qty_per_ticket SMALLINT NOT NULL DEFAULT 1
        CHECK (max_qty_per_ticket >= 1),
    ADD COLUMN IF NOT EXISTS refund_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS print_name TEXT,
    ADD COLUMN IF NOT EXISTS ffd_payment_object SMALLINT NOT NULL DEFAULT 4,
    ADD COLUMN IF NOT EXISTS ffd_payment_method SMALLINT NOT NULL DEFAULT 4,
    ADD COLUMN IF NOT EXISTS ffd_vat_tag SMALLINT NOT NULL DEFAULT 6
        CHECK (ffd_vat_tag BETWEEN 1 AND 12),
    ADD COLUMN IF NOT EXISTS ffd_measure_code SMALLINT NOT NULL DEFAULT 0;

UPDATE sales.nomenclature_items
SET print_name = name
WHERE print_name IS NULL;

ALTER TABLE sales.nomenclature_items
    ALTER COLUMN print_name SET NOT NULL;

ALTER TABLE sales.nomenclature_items
    ADD CONSTRAINT chk_nomenclature_price_bounds
        CHECK (min_price_cents IS NULL OR max_price_cents IS NULL OR min_price_cents <= max_price_cents);

CREATE TABLE IF NOT EXISTS sales.order_nomenclature_lines (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES sales.orders(id) ON DELETE CASCADE,
    order_item_id UUID REFERENCES sales.order_items(id) ON DELETE CASCADE,
    nomenclature_item_id UUID NOT NULL REFERENCES sales.nomenclature_items(id),
    quantity SMALLINT NOT NULL CHECK (quantity >= 1),
    unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents >= 0),
    total_price_cents BIGINT NOT NULL CHECK (total_price_cents >= 0),
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    print_name TEXT NOT NULL,
    ffd_payment_object SMALLINT NOT NULL,
    ffd_payment_method SMALLINT NOT NULL,
    ffd_vat_tag SMALLINT NOT NULL,
    ffd_measure_code SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_nomenclature_lines_order
    ON sales.order_nomenclature_lines (order_id);

CREATE INDEX IF NOT EXISTS idx_order_nomenclature_lines_order_item
    ON sales.order_nomenclature_lines (order_item_id);

CREATE TABLE IF NOT EXISTS sales.nomenclature_refunds (
    id UUID PRIMARY KEY,
    order_nomenclature_line_id UUID NOT NULL REFERENCES sales.order_nomenclature_lines(id),
    policy_id UUID NOT NULL REFERENCES sales.refund_policies(id),
    quantity SMALLINT NOT NULL CHECK (quantity >= 1),
    penalty_cents BIGINT NOT NULL DEFAULT 0,
    service_fee_cents BIGINT NOT NULL DEFAULT 0,
    refund_cents BIGINT NOT NULL CHECK (refund_cents >= 0),
    refund_type TEXT NOT NULL,
    fiscal_receipt_id UUID REFERENCES sales.fiscal_receipts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_nomenclature_refunds_line
    ON sales.nomenclature_refunds (order_nomenclature_line_id);
