-- Direct invitations have no public request or submission. Existing request matches are unchanged.
ALTER TABLE match_relationships ALTER COLUMN request_id DROP NOT NULL;
ALTER TABLE match_rounds ALTER COLUMN submission_id DROP NOT NULL;
CREATE UNIQUE INDEX matches_direct_pair ON match_relationships(requester_id,tutor_id) WHERE request_id IS NULL;

-- Preserve the request/submission invariant for both relationship types.
CREATE FUNCTION check_match_round_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_request uuid;
BEGIN
    SELECT request_id INTO source_request FROM match_relationships WHERE id=NEW.match_id;
    IF (source_request IS NULL) <> (NEW.submission_id IS NULL) THEN
        RAISE EXCEPTION 'Match source and submission must agree';
    END IF;
    IF NEW.submission_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM request_submissions WHERE id=NEW.submission_id AND request_id=source_request
    ) THEN RAISE EXCEPTION 'Submission must belong to the matched request'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER match_round_source BEFORE INSERT OR UPDATE ON match_rounds
    FOR EACH ROW EXECUTE FUNCTION check_match_round_source();
