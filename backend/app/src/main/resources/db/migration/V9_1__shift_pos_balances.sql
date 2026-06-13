ALTER TABLE sales.shifts
    ADD COLUMN IF NOT EXISTS pos_id TEXT,
    ADD COLUMN IF NOT EXISTS opening_balance_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS closing_balance_cents BIGINT;

UPDATE sales.shifts
SET pos_id = cashier_name
WHERE pos_id IS NULL;

ALTER TABLE sales.shifts
    ALTER COLUMN pos_id SET NOT NULL;

DROP INDEX IF EXISTS sales.idx_shifts_cashier_open;

CREATE UNIQUE INDEX IF NOT EXISTS idx_shifts_pos_open
    ON sales.shifts (pos_id)
    WHERE status = 'OPEN';

CREATE UNIQUE INDEX IF NOT EXISTS idx_shifts_cashier_open
    ON sales.shifts (cashier_name)
    WHERE status = 'OPEN';
