CREATE TYPE iam.user_type AS ENUM ('USER', 'SERVICE');
CREATE TYPE iam.role_type AS ENUM ('SYSTEM', 'CUSTOM');

CREATE TABLE iam.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    login VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(72) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(20),
    user_type iam.user_type NOT NULL DEFAULT 'USER',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_superuser BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMPTZ,
    created_by UUID REFERENCES iam.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_login_format CHECK (login ~ '^[a-z0-9_]{3,50}$')
);

CREATE UNIQUE INDEX uq_one_superuser ON iam.users (is_superuser) WHERE is_superuser = TRUE;

CREATE TABLE iam.roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    role_type iam.role_type NOT NULL DEFAULT 'CUSTOM',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES iam.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE iam.role_permissions (
    role_id UUID NOT NULL REFERENCES iam.roles(id) ON DELETE CASCADE,
    permission VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE iam.station_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES iam.users(id),
    station_id UUID NOT NULL,
    role_id UUID NOT NULL REFERENCES iam.roles(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_by UUID NOT NULL REFERENCES iam.users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES iam.users(id)
);

CREATE UNIQUE INDEX uq_active_station_assignment
    ON iam.station_assignments (user_id, station_id)
    WHERE is_active = TRUE;

CREATE TABLE iam.refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES iam.users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent TEXT,
    ip_address INET
);

CREATE TABLE iam.service_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES iam.users(id),
    name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    last_used_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES iam.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES iam.users(id)
);

CREATE TABLE iam.auth_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES iam.users(id),
    event_type VARCHAR(40) NOT NULL,
    ip_address INET,
    user_agent TEXT,
    details_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_login ON iam.users(login);
CREATE INDEX idx_refresh_tokens_user ON iam.refresh_tokens(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_station_assignments_user ON iam.station_assignments(user_id, is_active);
