ALTER TABLE inventory.seats
    ADD COLUMN IF NOT EXISTS requires_reaccommodation BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_seats_reaccommodation
    ON inventory.seats (trip_id)
    WHERE requires_reaccommodation = TRUE;
