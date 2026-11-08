CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    account varchar(32) NOT NULL,
    account_key varchar(32) NOT NULL UNIQUE,
    email varchar(254) NOT NULL,
    email_key varchar(254) NOT NULL UNIQUE,
    password_hash text NOT NULL,
    adult_confirmed_at timestamptz NOT NULL,
    adult_declaration_version text NOT NULL DEFAULT 'adult-18-v1',
    email_verified boolean NOT NULL DEFAULT false,
    business_identity text CHECK (business_identity IN ('REQUESTER', 'TUTOR')),
    operator_granted boolean NOT NULL DEFAULT false,
    registration_flow_id uuid NOT NULL UNIQUE,
    registration_context_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE profiles (
    account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    display_name text NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0,
    category_codes jsonb NOT NULL DEFAULT '[]',
    service_modes jsonb NOT NULL DEFAULT '[]',
    area_codes jsonb NOT NULL DEFAULT '[]',
    introduction text NOT NULL DEFAULT '',
    price_min numeric(14,2),
    price_max numeric(14,2),
    availability_text text NOT NULL DEFAULT '',
    is_public boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((price_min IS NULL AND price_max IS NULL) OR
           (price_min IS NOT NULL AND price_max IS NOT NULL AND price_min > 0 AND price_max >= price_min)),
    CHECK (NOT is_public OR (length(trim(display_name)) > 0 AND length(trim(introduction)) > 0
           AND jsonb_array_length(category_codes) > 0 AND jsonb_array_length(service_modes) > 0
           AND (NOT service_modes @> '["OFFLINE"]'::jsonb OR jsonb_array_length(area_codes) > 0)))
);
CREATE TABLE email_tokens (
    token_hash char(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose text NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX email_tokens_account ON email_tokens(account_id, purpose);
CREATE TABLE auth_sessions (
    token_hash char(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_sessions_account ON auth_sessions(account_id);
CREATE TABLE auth_rate_limits (
    bucket_key char(64) PRIMARY KEY,
    attempts integer NOT NULL,
    window_end timestamptz NOT NULL
);
CREATE TABLE profile_mutation_receipts (
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    action text NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    request_hash char(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, action, idempotency_key)
);
