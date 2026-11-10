-- Private values never enter public profiles, match snapshots, or action receipts.
CREATE TABLE private_contacts (
    account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    phone text CHECK (phone IS NULL OR phone ~ '^1[0-9]{10}$'),
    wechat text CHECK (wechat IS NULL OR (char_length(wechat) BETWEEN 1 AND 64 AND wechat !~ '[[:space:][:cntrl:]]')),
    phone_version bigint NOT NULL DEFAULT 0 CHECK (phone_version>=0),
    wechat_version bigint NOT NULL DEFAULT 0 CHECK (wechat_version>=0)
);
CREATE TABLE contact_grants (
    round_id uuid PRIMARY KEY,
    match_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    requester_agreed boolean NOT NULL DEFAULT false,
    tutor_agreed boolean NOT NULL DEFAULT false,
    requester_phone_version bigint CHECK (requester_phone_version>=0),
    requester_wechat_version bigint CHECK (requester_wechat_version>=0),
    tutor_phone_version bigint CHECK (tutor_phone_version>=0),
    tutor_wechat_version bigint CHECK (tutor_wechat_version>=0),
    requester_reconfirm boolean NOT NULL DEFAULT false,
    tutor_reconfirm boolean NOT NULL DEFAULT false,
    FOREIGN KEY(match_id,round_id) REFERENCES match_rounds(match_id,id) ON DELETE CASCADE,
    CHECK (NOT requester_agreed OR (NOT requester_reconfirm AND (requester_phone_version IS NOT NULL OR requester_wechat_version IS NOT NULL))),
    CHECK (NOT tutor_agreed OR (NOT tutor_reconfirm AND (tutor_phone_version IS NOT NULL OR tutor_wechat_version IS NOT NULL)))
);
CREATE INDEX contact_grants_match ON contact_grants(match_id);
CREATE TABLE contact_action_receipts (
    actor_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    action text NOT NULL CHECK (action IN ('AGREE','WITHDRAW')),
    idempotency_key varchar(100) NOT NULL,
    match_id uuid NOT NULL,
    round_id uuid NOT NULL,
    fingerprint text NOT NULL,
    PRIMARY KEY(actor_id,action,idempotency_key),
    FOREIGN KEY(match_id,round_id) REFERENCES match_rounds(match_id,id) ON DELETE CASCADE
);
