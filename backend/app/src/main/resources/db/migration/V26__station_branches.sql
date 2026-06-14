ALTER TABLE scheduling.service_stations
    ADD COLUMN IF NOT EXISTS point_id UUID REFERENCES scheduling.points(id),
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS contact_phone TEXT;

UPDATE scheduling.service_stations ss
SET point_id = p.id
FROM scheduling.points p
WHERE ss.point_id IS NULL AND p.code = ss.code;

CREATE TABLE IF NOT EXISTS scheduling.station_provisioning_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id  UUID NOT NULL REFERENCES scheduling.service_stations(id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_by  UUID REFERENCES iam.users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_station_provisioning_station
    ON scheduling.station_provisioning_tokens (station_id, created_at DESC);

CREATE TABLE IF NOT EXISTS scheduling.station_agent_status (
    station_id     UUID PRIMARY KEY REFERENCES scheduling.service_stations(id) ON DELETE CASCADE,
    connected      BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at   TIMESTAMPTZ,
    agent_version  TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
