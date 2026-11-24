ALTER TABLE accounts ADD COLUMN is_frozen boolean NOT NULL DEFAULT false;
ALTER TABLE reviews
    ADD COLUMN is_hidden boolean NOT NULL DEFAULT false,
    ADD COLUMN hidden_reason text,
    ADD CHECK (is_hidden = (hidden_reason IS NOT NULL));
ALTER TABLE verifications ADD COLUMN delete_requested boolean NOT NULL DEFAULT false;
ALTER TABLE verifications ALTER COLUMN proof_path DROP NOT NULL;
ALTER TABLE verifications ADD CHECK (proof_path IS NOT NULL OR status = 'approved');

CREATE TABLE admin_logs (
    id uuid PRIMARY KEY,
    admin_id uuid NOT NULL,
    action_type text NOT NULL,
    target_type text NOT NULL,
    target_id uuid NOT NULL,
    reason text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX admin_logs_target ON admin_logs(target_type, target_id, created_at DESC);
