CREATE TABLE verifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    verify_type text NOT NULL CHECK (verify_type IN ('student_id', 'skill_cert')),
    proof_path text NOT NULL UNIQUE,
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    reject_reason text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reviewed_at timestamptz,
    CHECK ((status = 'pending') = (reviewed_at IS NULL)),
    CHECK ((status = 'rejected') = (reject_reason IS NOT NULL))
);

CREATE UNIQUE INDEX verifications_active ON verifications(user_id, verify_type)
    WHERE status IN ('pending', 'approved');
CREATE INDEX verifications_owner_history ON verifications(user_id, verify_type, created_at DESC);
CREATE INDEX verifications_queue ON verifications(status, created_at, id);
