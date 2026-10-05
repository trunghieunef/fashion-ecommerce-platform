-- TASK:USR-02 part 2b: RBAC (05 section 4), audit (05 section 3) and API idempotency (03 section 1.3).
-- Services check permission codes, never role names; the seed follows 13 section 2.

CREATE TABLE roles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(32) NOT NULL UNIQUE,
  name text NOT NULL
);

CREATE TABLE permissions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code text NOT NULL UNIQUE
);

CREATE TABLE user_roles (
  user_id uuid NOT NULL REFERENCES users(id),
  role_id uuid NOT NULL REFERENCES roles(id),
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
  role_id uuid NOT NULL REFERENCES roles(id),
  permission_id uuid NOT NULL REFERENCES permissions(id),
  PRIMARY KEY (role_id, permission_id)
);

INSERT INTO roles(code, name) VALUES
  ('SUPER_ADMIN', 'Super admin'),
  ('OPS', 'Operations'),
  ('FINANCE', 'Finance'),
  ('MARKETING', 'Marketing');

WITH grants(role, permission) AS (VALUES
  ('SUPER_ADMIN', 'user.manage'),
  ('OPS', 'catalog.write'), ('OPS', 'order.read'), ('OPS', 'order.fulfill'), ('OPS', 'order.cancel'),
  ('OPS', 'order.return'), ('OPS', 'inventory.read'), ('OPS', 'inventory.adjust'), ('OPS', 'shipping.read'),
  ('OPS', 'shipping.self_event'), ('OPS', 'shipping.rules'), ('OPS', 'review.moderate'),
  ('OPS', 'report.orders'), ('OPS', 'report.stock'),
  ('FINANCE', 'order.read'), ('FINANCE', 'payment.read'), ('FINANCE', 'payment.refund'),
  ('FINANCE', 'payment.settle'), ('FINANCE', 'payment.reconcile'), ('FINANCE', 'report.cash'),
  ('MARKETING', 'promotion.manage'), ('MARKETING', 'notification.template'),
  ('MARKETING', 'notification.campaign')
), seeded AS (
  INSERT INTO permissions(code) SELECT DISTINCT permission FROM grants RETURNING id, code
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM grants
JOIN roles r ON r.code = grants.role
JOIN seeded p ON p.code = grants.permission;

-- actor_id NULL means SYSTEM (admin bootstrap); every admin mutation records its real actor.
CREATE TABLE audit_logs (
  id uuid PRIMARY KEY,
  actor_id uuid,
  action text NOT NULL,
  resource_type text NOT NULL,
  resource_id text NOT NULL,
  reason text,
  before_data jsonb,
  after_data jsonb,
  request_id uuid NOT NULL,
  source_ip inet,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_logs_resource_idx ON audit_logs (resource_type, resource_id, created_at);

-- Append-only by privilege: the runtime role keeps INSERT/SELECT only.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM user_runtime;

-- From services/platform-durability/src/main/resources/db/platform/durability.sql.
CREATE TABLE idempotency_requests (
  actor_key text NOT NULL,
  operation text NOT NULL,
  key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL,
  resource_id uuid,
  status varchar(32) NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'COMPLETED')),
  response_code int,
  response_body jsonb,
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_key, operation, key),
  CHECK ((status = 'COMPLETED') = (response_code IS NOT NULL))
);
