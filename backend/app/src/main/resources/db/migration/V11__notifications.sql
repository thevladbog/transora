CREATE TABLE IF NOT EXISTS notifications.display_boards (
    id UUID PRIMARY KEY,
    station_code TEXT NOT NULL,
    board_type TEXT NOT NULL,
    platform_number TEXT,
    name TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_display_board_platform CHECK (
        (board_type = 'PLATFORM' AND platform_number IS NOT NULL)
        OR (board_type <> 'PLATFORM' AND platform_number IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_display_boards_station
    ON notifications.display_boards (station_code, is_active);

CREATE TABLE IF NOT EXISTS notifications.board_snapshots (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES notifications.display_boards(id) ON DELETE CASCADE,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_board_snapshots_board_id
    ON notifications.board_snapshots (board_id, created_at DESC);

CREATE TABLE IF NOT EXISTS notifications.announcement_queue (
    id UUID PRIMARY KEY,
    station_code TEXT NOT NULL,
    priority TEXT NOT NULL DEFAULT 'MEDIUM',
    text TEXT NOT NULL,
    trip_id UUID,
    status TEXT NOT NULL DEFAULT 'QUEUED',
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_announcement_queue_station
    ON notifications.announcement_queue (station_code, status, priority);
