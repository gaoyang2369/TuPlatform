-- One relationship per request/tutor; a new intention always gets a fresh round.
-- RESTRICT preserves submitted content and relationships when a revision draft expires.
CREATE TABLE match_relationships (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES service_requests(id),
    requester_id uuid NOT NULL REFERENCES accounts(id),
    tutor_id uuid NOT NULL REFERENCES accounts(id),
    current_round_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(request_id,tutor_id),
    CHECK (requester_id<>tutor_id)
);
CREATE TABLE match_rounds (
    id uuid PRIMARY KEY,
    match_id uuid NOT NULL REFERENCES match_relationships(id) ON DELETE CASCADE,
    submission_id uuid NOT NULL REFERENCES request_submissions(id),
    initiator_id uuid NOT NULL REFERENCES accounts(id),
    initiator_role text NOT NULL CHECK (initiator_role IN ('REQUESTER','TUTOR')),
    state text NOT NULL CHECK (state IN ('WAITING','MUTUAL','REJECTED','WITHDRAWN')),
    teacher_profile_version bigint NOT NULL,
    submission_snapshot jsonb NOT NULL CHECK (jsonb_typeof(submission_snapshot)='object'),
    teacher_snapshot jsonb NOT NULL CHECK (jsonb_typeof(teacher_snapshot)='object'),
    initiated_at timestamptz NOT NULL DEFAULT now(),
    responded_by uuid REFERENCES accounts(id),
    responded_at timestamptz,
    UNIQUE(match_id,id),
    CHECK ((state='WAITING' AND responded_by IS NULL AND responded_at IS NULL)
        OR (state<>'WAITING' AND responded_by IS NOT NULL AND responded_at IS NOT NULL))
);
ALTER TABLE match_relationships ADD CONSTRAINT match_current_round
    FOREIGN KEY(id,current_round_id) REFERENCES match_rounds(match_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX matches_requester ON match_relationships(requester_id,id DESC);
CREATE INDEX matches_tutor ON match_relationships(tutor_id,id DESC);
CREATE INDEX match_rejection_history ON match_rounds(match_id,submission_id,initiator_id) WHERE state='REJECTED';
CREATE TABLE match_action_receipts (
    actor_id uuid NOT NULL REFERENCES accounts(id),
    action text NOT NULL CHECK(action IN ('START','ACCEPT','REJECT','WITHDRAW')),
    idempotency_key varchar(100) NOT NULL,
    match_id uuid NOT NULL REFERENCES match_relationships(id) ON DELETE CASCADE,
    fingerprint text NOT NULL,
    result jsonb NOT NULL,
    PRIMARY KEY(actor_id,action,idempotency_key)
);
