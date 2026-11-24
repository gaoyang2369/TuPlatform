ALTER TABLE accounts ADD COLUMN policies_accepted_at timestamptz,
    ADD COLUMN publishing_policies_accepted_at timestamptz;
ALTER TABLE verifications ADD COLUMN policies_accepted_at timestamptz;

CREATE TABLE analytics_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_name text NOT NULL CHECK (event_name IN
        ('page_view_teachers','open_teacher_detail','send_match_intent','match_confirmed','view_contact')),
    user_id uuid REFERENCES accounts(id) ON DELETE CASCADE,
    session_id uuid NOT NULL,
    meta_json jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX analytics_events_period ON analytics_events(created_at, event_name);
