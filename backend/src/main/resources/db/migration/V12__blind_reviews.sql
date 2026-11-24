-- Each match_rounds row represents one intention/cooperation with its own review window.
CREATE TABLE reviews (
    id uuid PRIMARY KEY,
    match_id uuid NOT NULL REFERENCES match_rounds(id) ON DELETE CASCADE,
    reviewer_id uuid NOT NULL REFERENCES accounts(id),
    reviewee_id uuid NOT NULL REFERENCES accounts(id),
    reviewer_role text NOT NULL CHECK (reviewer_role IN ('parent','teacher')),
    overall_score smallint NOT NULL CHECK (overall_score BETWEEN 1 AND 5),
    sub_scores_json jsonb NOT NULL CHECK (jsonb_typeof(sub_scores_json)='object'),
    tags_json jsonb NOT NULL CHECK (jsonb_typeof(tags_json)='array' AND jsonb_array_length(tags_json)<=4),
    content varchar(300),
    reply_content varchar(200),
    replied_at timestamptz,
    is_published boolean NOT NULL DEFAULT false,
    reveal_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    submission_key varchar(100),
    reply_key varchar(100),
    UNIQUE(match_id, reviewer_role),
    CHECK (reviewer_id <> reviewee_id),
    CHECK ((reply_content IS NULL) = (replied_at IS NULL)),
    CHECK (reply_content IS NULL OR is_published)
);
CREATE INDEX reviews_due ON reviews(reveal_at) WHERE NOT is_published;
