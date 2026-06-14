CREATE TABLE IF NOT EXISTS scheduling.points (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    city TEXT NOT NULL DEFAULT '',
    address TEXT,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    timezone TEXT NOT NULL DEFAULT 'Europe/Moscow',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_points_active ON scheduling.points (is_active);
CREATE INDEX IF NOT EXISTS idx_points_geo ON scheduling.points (latitude, longitude);

CREATE TABLE IF NOT EXISTS sales.nomenclature_items (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('BAGGAGE', 'INSURANCE', 'SERVICE', 'OTHER')),
    price_cents BIGINT NOT NULL CHECK (price_cents >= 0),
    refund_policy_id UUID REFERENCES sales.refund_policies(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_nomenclature_active ON sales.nomenclature_items (is_active);

CREATE TABLE IF NOT EXISTS sales.tariff_profiles (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    route_id UUID REFERENCES scheduling.routes(id),
    valid_from DATE,
    valid_to DATE,
    refund_policy_id UUID REFERENCES sales.refund_policies(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tariff_profile_dates CHECK (
        valid_from IS NULL OR valid_to IS NULL OR valid_to >= valid_from
    )
);

CREATE INDEX IF NOT EXISTS idx_tariff_profiles_route_active
    ON sales.tariff_profiles (route_id, is_active);

CREATE TABLE IF NOT EXISTS sales.tariff_profile_stops (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES sales.tariff_profiles(id) ON DELETE CASCADE,
    point_id UUID NOT NULL REFERENCES scheduling.points(id),
    stop_order SMALLINT NOT NULL CHECK (stop_order >= 1),
    CONSTRAINT uq_tariff_profile_stop_order UNIQUE (profile_id, stop_order),
    CONSTRAINT uq_tariff_profile_stop_point UNIQUE (profile_id, point_id)
);

CREATE TABLE IF NOT EXISTS sales.tariff_cells (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES sales.tariff_profiles(id) ON DELETE CASCADE,
    from_stop_order SMALLINT NOT NULL CHECK (from_stop_order >= 1),
    to_stop_order SMALLINT NOT NULL CHECK (to_stop_order >= 2),
    price_cents BIGINT NOT NULL CHECK (price_cents >= 0),
    is_mirror_override BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_tariff_cell_stops CHECK (to_stop_order > from_stop_order),
    CONSTRAINT uq_tariff_cell_segment UNIQUE (profile_id, from_stop_order, to_stop_order)
);

CREATE INDEX IF NOT EXISTS idx_tariff_cells_profile
    ON sales.tariff_cells (profile_id);

INSERT INTO scheduling.points (id, code, name, city, address, latitude, longitude, timezone, is_active, created_at)
SELECT
    id,
    code,
    name,
    city,
    address,
    55.7558,
    37.6173,
    timezone,
    is_active,
    created_at
FROM scheduling.service_stations
ON CONFLICT (code) DO NOTHING;
