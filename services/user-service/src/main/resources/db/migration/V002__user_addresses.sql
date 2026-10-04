-- TASK:USR-02 part 2a: address book (05 section 4). At most one default per user; the service
-- changes the default under the user's row lock in one transaction.
CREATE TABLE user_addresses (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  recipient_name text NOT NULL,
  phone varchar(32) NOT NULL,
  province_code text NOT NULL,
  district_code text,
  ward_code text NOT NULL,
  address_line text NOT NULL,
  is_default boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX user_addresses_user_idx ON user_addresses (user_id);
CREATE UNIQUE INDEX user_addresses_one_default_uq ON user_addresses (user_id) WHERE is_default;
