CREATE TABLE service_requests (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_REVIEW','REJECTED','OPEN','EXPIRED')),
    version bigint NOT NULL DEFAULT 0,
    fields jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(fields) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_saved_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX service_requests_owner_saved ON service_requests(owner_id, last_saved_at DESC, id DESC);
CREATE TABLE request_creation_receipts (
    owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    idempotency_key varchar(100) NOT NULL,
    request_id uuid REFERENCES service_requests(id) ON DELETE SET NULL,
    PRIMARY KEY (owner_id, idempotency_key)
);
