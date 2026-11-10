CREATE TABLE request_submissions (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES service_requests(id) ON DELETE CASCADE,
    fields jsonb NOT NULL CHECK (jsonb_typeof(fields) = 'object'),
    publisher_display_name text NOT NULL,
    publisher_declaration text NOT NULL CHECK (publisher_declaration IN ('SELF_ADULT','GUARDIAN_OF_MINOR')),
    declaration_version text NOT NULL CHECK (declaration_version = 'publisher-v1'),
    submitted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > submitted_at),
    status text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED')),
    version bigint NOT NULL DEFAULT 0
);
ALTER TABLE service_requests ADD COLUMN latest_submission_id uuid REFERENCES request_submissions(id) ON DELETE SET NULL;
CREATE INDEX request_submissions_queue ON request_submissions(status, submitted_at DESC, id DESC);
CREATE INDEX request_submissions_request ON request_submissions(request_id, submitted_at DESC, id DESC);

CREATE TABLE review_decisions (
    submission_id uuid PRIMARY KEY REFERENCES request_submissions(id) ON DELETE CASCADE,
    reviewer_id uuid REFERENCES accounts(id) ON DELETE SET NULL,
    decision text NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    reason text,
    reviewed_at timestamptz NOT NULL,
    CHECK ((decision = 'REJECT' AND reason IS NOT NULL AND length(btrim(reason)) > 0) OR (decision = 'APPROVE' AND reason IS NULL))
);

-- Receipts are scoped to these request actions; no general workflow/operation platform.
CREATE TABLE request_action_receipts (
    actor_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    action text NOT NULL CHECK (action IN ('SUBMIT','REVISE','REVIEW')),
    idempotency_key varchar(100) NOT NULL,
    request_id uuid NOT NULL REFERENCES service_requests(id) ON DELETE CASCADE,
    fingerprint text NOT NULL,
    result jsonb NOT NULL,
    PRIMARY KEY(actor_id, action, idempotency_key)
);
