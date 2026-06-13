CREATE TABLE IF NOT EXISTS app.processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE sales.tickets
    ADD COLUMN IF NOT EXISTS ticket_number TEXT;

UPDATE sales.tickets
SET ticket_number = CONCAT('LEGACY-', LEFT(id::text, 8))
WHERE ticket_number IS NULL;

ALTER TABLE sales.tickets
    ALTER COLUMN ticket_number SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_tickets_ticket_number
    ON sales.tickets (ticket_number);

CREATE TABLE IF NOT EXISTS sales.ticket_daily_sequences (
    station_code TEXT NOT NULL,
    issue_date DATE NOT NULL,
    last_value BIGINT NOT NULL,
    PRIMARY KEY (station_code, issue_date)
);

CREATE TABLE IF NOT EXISTS documents.generated_documents (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES sales.tickets(id),
    content_type TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_generated_documents_ticket_id
    ON documents.generated_documents (ticket_id);
