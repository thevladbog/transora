CREATE TABLE IF NOT EXISTS documents.document_templates (
    id UUID PRIMARY KEY,
    doc_type TEXT NOT NULL,
    name TEXT NOT NULL,
    version SMALLINT NOT NULL DEFAULT 1,
    template_path TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_document_templates_type_version UNIQUE (doc_type, version)
);

CREATE TABLE IF NOT EXISTS documents.document_requests (
    id UUID PRIMARY KEY,
    doc_type TEXT NOT NULL,
    template_id UUID REFERENCES documents.document_templates(id),
    status TEXT NOT NULL DEFAULT 'PENDING',
    requested_by TEXT NOT NULL DEFAULT 'system',
    params_json JSONB NOT NULL,
    params_hash TEXT NOT NULL,
    error_message TEXT,
    processing_ms INTEGER,
    result_document_id UUID REFERENCES documents.generated_documents(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_document_requests_type_hash
    ON documents.document_requests (doc_type, params_hash)
    WHERE status = 'COMPLETED';

ALTER TABLE documents.generated_documents
    ADD COLUMN IF NOT EXISTS doc_type TEXT NOT NULL DEFAULT 'TICKET',
    ADD COLUMN IF NOT EXISTS trip_id UUID REFERENCES scheduling.trips(id),
    ADD COLUMN IF NOT EXISTS request_id UUID REFERENCES documents.document_requests(id);

ALTER TABLE documents.generated_documents
    ALTER COLUMN ticket_id DROP NOT NULL;

CREATE TABLE IF NOT EXISTS documents.trip_document_sets (
    trip_id UUID PRIMARY KEY REFERENCES scheduling.trips(id) ON DELETE CASCADE,
    manifest_doc_id UUID REFERENCES documents.generated_documents(id),
    boarding_sheet_doc_id UUID REFERENCES documents.generated_documents(id),
    carrier_report_doc_id UUID REFERENCES documents.generated_documents(id),
    manifest_version SMALLINT NOT NULL DEFAULT 0,
    last_generated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS documents.print_log (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents.generated_documents(id),
    ticket_id UUID,
    trip_id UUID,
    printed_by TEXT NOT NULL DEFAULT 'system',
    station_code TEXT,
    pos_id TEXT,
    print_type TEXT NOT NULL,
    printed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_print_log_document_id
    ON documents.print_log (document_id);
