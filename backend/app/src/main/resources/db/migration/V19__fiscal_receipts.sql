CREATE TABLE IF NOT EXISTS sales.fiscal_receipts (
    id UUID PRIMARY KEY,
    shift_id UUID REFERENCES sales.shifts(id),
    order_id UUID REFERENCES sales.orders(id),
    refund_id UUID REFERENCES sales.refunds(id),
    receipt_type TEXT NOT NULL,
    amount_cents BIGINT NOT NULL DEFAULT 0,
    fiscal_sign TEXT NOT NULL,
    fiscal_doc_no BIGINT NOT NULL,
    fiscal_drive_no TEXT NOT NULL DEFAULT 'MOCK-FN',
    ofd_status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fiscal_receipts_shift
    ON sales.fiscal_receipts (shift_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fiscal_receipts_shift_z_report
    ON sales.fiscal_receipts (shift_id)
    WHERE receipt_type = 'Z_REPORT';

ALTER TABLE sales.orders
    ADD COLUMN IF NOT EXISTS fiscal_receipt_id UUID REFERENCES sales.fiscal_receipts(id);

ALTER TABLE sales.refunds
    ADD COLUMN IF NOT EXISTS fiscal_receipt_id UUID REFERENCES sales.fiscal_receipts(id);

ALTER TABLE sales.shifts
    ADD COLUMN IF NOT EXISTS z_report_fiscal_receipt_id UUID REFERENCES sales.fiscal_receipts(id);
