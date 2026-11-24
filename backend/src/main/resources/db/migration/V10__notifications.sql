CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    event_type text NOT NULL,
    event_id uuid NOT NULL,
    title text NOT NULL,
    content text NOT NULL,
    target_url text NOT NULL,
    channel text NOT NULL CHECK (channel IN ('email','in_app')),
    is_read boolean NOT NULL DEFAULT false,
    sent_status text NOT NULL DEFAULT 'pending' CHECK (sent_status IN ('pending','sent','logged','failed')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, event_type, event_id, channel)
);
CREATE INDEX notifications_delivery ON notifications(next_attempt_at, created_at)
    WHERE channel='email' AND sent_status='pending';
CREATE INDEX notifications_unread ON notifications(user_id, event_type, created_at) WHERE NOT is_read;
