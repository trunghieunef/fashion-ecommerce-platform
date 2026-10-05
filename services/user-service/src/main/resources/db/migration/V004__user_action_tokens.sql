-- TASK:USR-01 part 1b-i: one-time password-reset tokens (05 section 4). Only the SHA-256 of the
-- token is stored; the token itself reaches notification through the encrypted Redis handoff.
CREATE TABLE user_action_tokens (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  purpose varchar(16) NOT NULL CHECK (purpose IN ('RESET_PASSWORD', 'EMAIL_VERIFY')),
  target_email varchar(254) CHECK (target_email = lower(target_email)),
  token_hash char(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (purpose <> 'EMAIL_VERIFY' OR target_email IS NOT NULL)
);

CREATE INDEX user_action_tokens_expiry_idx ON user_action_tokens (expires_at);
CREATE INDEX user_action_tokens_user_idx ON user_action_tokens (user_id, purpose) WHERE used_at IS NULL;
