ALTER TABLE match_rounds DROP CONSTRAINT match_rounds_state_check,
    ADD CHECK (state IN ('WAITING','MUTUAL','REJECTED','WITHDRAWN','in_cooperation','completed','closed_no_deal')),
    ADD COLUMN contacts_exchanged_at timestamptz,
    ADD COLUMN cooperation_requested_by uuid REFERENCES accounts(id),
    ADD COLUMN cooperation_requested_at timestamptz,
    ADD COLUMN cooperation_confirmed_at timestamptz,
    ADD COLUMN completion_requested_by uuid REFERENCES accounts(id),
    ADD COLUMN completion_requested_at timestamptz,
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN review_deadline_at timestamptz,
    ADD COLUMN followup_reminded_at timestamptz,
    ADD CHECK ((cooperation_requested_by IS NULL) = (cooperation_requested_at IS NULL)),
    ADD CHECK ((completion_requested_by IS NULL) = (completion_requested_at IS NULL)),
    ADD CHECK ((state IN ('in_cooperation','completed')) = (cooperation_confirmed_at IS NOT NULL)),
    ADD CHECK ((state = 'completed') = (completed_at IS NOT NULL)),
    ADD CHECK ((state = 'completed') = (review_deadline_at IS NOT NULL));

ALTER TABLE match_action_receipts DROP CONSTRAINT match_action_receipts_action_check,
    ADD CHECK (action IN ('START','ACCEPT','REJECT','WITHDRAW','request-cooperation','confirm-cooperation',
        'close-no-deal','request-completion','confirm-completion','continue-cooperation'));

-- Legacy grants have no exchange timestamp. Give both participants a full grace period.
UPDATE match_rounds n SET contacts_exchanged_at = now()
FROM contact_grants g WHERE n.id=g.round_id AND n.state='MUTUAL' AND g.requester_agreed AND g.tutor_agreed;

-- ContactService invalidates both consents on a selected field edit. Keep the step-four
-- clock and any unconfirmed request in the same transaction as that authorization change.
CREATE FUNCTION track_contact_exchange() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.requester_agreed AND NEW.tutor_agreed THEN
        UPDATE match_rounds SET contacts_exchanged_at=now()
        WHERE id=NEW.round_id AND state='MUTUAL' AND contacts_exchanged_at IS NULL;
    ELSE
        UPDATE match_relationships SET version=version+1 WHERE current_round_id=NEW.round_id
            AND EXISTS (SELECT 1 FROM match_rounds WHERE id=NEW.round_id AND state='MUTUAL' AND cooperation_requested_at IS NOT NULL);
        UPDATE match_rounds SET contacts_exchanged_at=NULL, cooperation_requested_by=NULL, cooperation_requested_at=NULL
        WHERE id=NEW.round_id AND state='MUTUAL' AND contacts_exchanged_at IS NOT NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER track_contact_exchange AFTER INSERT OR UPDATE ON contact_grants
    FOR EACH ROW EXECUTE FUNCTION track_contact_exchange();

CREATE INDEX match_cooperation_due ON match_rounds(cooperation_requested_at)
    WHERE state='MUTUAL' AND cooperation_requested_at IS NOT NULL;
CREATE INDEX match_completion_due ON match_rounds(completion_requested_at)
    WHERE state='in_cooperation' AND completion_requested_at IS NOT NULL;
CREATE INDEX match_followup_due ON match_rounds(contacts_exchanged_at)
    WHERE state='MUTUAL' AND cooperation_requested_at IS NULL AND followup_reminded_at IS NULL;
