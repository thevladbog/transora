CREATE TABLE IF NOT EXISTS notifications.announcement_templates (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    template_text TEXT NOT NULL,
    priority TEXT NOT NULL DEFAULT 'MEDIUM',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO notifications.announcement_templates (id, code, name, template_text, priority)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'DEPARTURE_30', 'Departure reminder 30 min',
     'Внимание! Через 30 минут отправление рейса {tripNumber} на платформе {platform}.', 'MEDIUM'),
    ('00000000-0000-0000-0000-000000000102', 'DEPARTURE_15', 'Departure reminder 15 min',
     'Внимание! Через 15 минут отправление рейса {tripNumber} на платформе {platform}.', 'HIGH')
ON CONFLICT (code) DO NOTHING;

ALTER TABLE notifications.display_boards
    ADD COLUMN IF NOT EXISTS agent_id TEXT,
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_display_boards_agent_id
    ON notifications.display_boards (agent_id)
    WHERE agent_id IS NOT NULL;

ALTER TABLE notifications.announcement_queue
    ADD COLUMN IF NOT EXISTS audio_path TEXT,
    ADD COLUMN IF NOT EXISTS template_code TEXT;

CREATE TABLE IF NOT EXISTS notifications.station_announcement_settings (
    station_code TEXT PRIMARY KEY,
    queue_paused BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS notifications.scheduled_announcement_log (
    id UUID PRIMARY KEY,
    station_code TEXT NOT NULL,
    trip_id UUID NOT NULL,
    template_code TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scheduled_announcement UNIQUE (station_code, trip_id, template_code)
);
