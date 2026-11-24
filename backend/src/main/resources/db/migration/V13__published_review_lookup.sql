CREATE INDEX reviews_published_by_recipient
    ON reviews (reviewee_id, reviewer_role, created_at DESC, id DESC)
    INCLUDE (overall_score) WHERE is_published;
