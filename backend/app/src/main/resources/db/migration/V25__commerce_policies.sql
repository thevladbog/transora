ALTER TABLE sales.refund_policies RENAME TO commerce_policies;
ALTER TABLE sales.refund_policy_tiers RENAME TO policy_tiers;

ALTER TABLE sales.commerce_policies
    ADD COLUMN IF NOT EXISTS policy_type TEXT NOT NULL DEFAULT 'REFUND'
        CHECK (policy_type IN ('REFUND', 'SALE')),
    ADD COLUMN IF NOT EXISTS nomenclature_item_id UUID REFERENCES sales.nomenclature_items(id),
    ADD COLUMN IF NOT EXISTS is_mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pricing_mode TEXT NOT NULL DEFAULT 'FROM_NOMENCLATURE'
        CHECK (pricing_mode IN ('FROM_NOMENCLATURE', 'FIXED', 'PERCENT')),
    ADD COLUMN IF NOT EXISTS fixed_price_cents BIGINT
        CHECK (fixed_price_cents IS NULL OR fixed_price_cents >= 0),
    ADD COLUMN IF NOT EXISTS percent_value NUMERIC(5, 2)
        CHECK (percent_value IS NULL OR (percent_value >= 0 AND percent_value <= 100)),
    ADD COLUMN IF NOT EXISTS percent_basis TEXT
        CHECK (percent_basis IS NULL OR percent_basis IN ('ROUTE_PRICE', 'REFUND_AMOUNT')),
    ADD COLUMN IF NOT EXISTS min_price_cents BIGINT
        CHECK (min_price_cents IS NULL OR min_price_cents >= 0),
    ADD COLUMN IF NOT EXISTS max_price_cents BIGINT
        CHECK (max_price_cents IS NULL OR max_price_cents >= 0);

ALTER TABLE sales.commerce_policies
    ADD CONSTRAINT chk_commerce_policy_price_bounds
        CHECK (min_price_cents IS NULL OR max_price_cents IS NULL OR min_price_cents <= max_price_cents);

ALTER TABLE sales.policy_tiers
    ADD COLUMN IF NOT EXISTS fixed_price_cents BIGINT
        CHECK (fixed_price_cents IS NULL OR fixed_price_cents >= 0),
    ADD COLUMN IF NOT EXISTS percent_value NUMERIC(5, 2)
        CHECK (percent_value IS NULL OR (percent_value >= 0 AND percent_value <= 100));

CREATE TABLE IF NOT EXISTS sales.route_commerce_policies (
    route_id UUID NOT NULL REFERENCES scheduling.routes(id) ON DELETE CASCADE,
    policy_id UUID NOT NULL REFERENCES sales.commerce_policies(id) ON DELETE CASCADE,
    priority SMALLINT NOT NULL CHECK (priority >= 1),
    PRIMARY KEY (route_id, policy_id),
    CONSTRAINT uq_route_policy_priority UNIQUE (route_id, priority)
);

CREATE INDEX IF NOT EXISTS idx_route_commerce_policies_route
    ON sales.route_commerce_policies (route_id, priority);

INSERT INTO sales.route_commerce_policies (route_id, policy_id, priority)
SELECT tp.route_id, tp.refund_policy_id, 1
FROM sales.tariff_profiles tp
WHERE tp.route_id IS NOT NULL
  AND tp.refund_policy_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sales.route_commerce_policies rcp
      WHERE rcp.route_id = tp.route_id AND rcp.policy_id = tp.refund_policy_id
  );
