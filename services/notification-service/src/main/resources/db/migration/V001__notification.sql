-- TASK:NOT-01: templates and notifications (05 section 11.2) plus the inbox table (PLT-03 durability.sql).
CREATE TABLE notification_templates (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'IN_APP')),
  template_key text NOT NULL CHECK (length(template_key) >= 1),
  locale varchar(2) NOT NULL CHECK (locale IN ('vi', 'en')),
  subject text,
  body text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  version int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (channel, template_key, locale)
);

CREATE TABLE notifications (
  id uuid PRIMARY KEY,
  event_id uuid NOT NULL,
  dedupe_key text NOT NULL CHECK (length(dedupe_key) >= 1),
  user_id uuid,
  recipient text NOT NULL CHECK (length(recipient) >= 3),
  channel varchar(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'IN_APP')),
  template_id uuid NOT NULL REFERENCES notification_templates(id),
  template_version int NOT NULL CHECK (template_version >= 1),
  rendered_subject text,
  rendered_body text NOT NULL,
  locale varchar(2) NOT NULL CHECK (locale IN ('vi', 'en')),
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN', 'EXPIRED', 'SUPPRESSED')),
  retry_count int NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  next_retry_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  provider_message_id text,
  expires_at timestamptz,
  sent_at timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (dedupe_key, channel, recipient),
  CHECK ((status = 'SENDING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX notifications_due_idx ON notifications (next_retry_at) WHERE status IN ('PENDING', 'UNKNOWN');
CREATE INDEX notifications_user_idx ON notifications (user_id, created_at);

CREATE TABLE processed_events (
  consumer_name text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_name, event_id)
);
