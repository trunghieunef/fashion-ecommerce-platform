#!/usr/bin/env bash
# Build host artifacts with the pinned toolchains, then start the local stack.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-infra/local/.env}"
[[ -f "$ENV_FILE" ]] || cp infra/local/.env.example "$ENV_FILE"

# Add keys introduced after this .env was created, with the example's synthetic values.
while IFS= read -r line; do
  [[ "$line" =~ ^([A-Z0-9_]+)= ]] || continue
  grep -q "^${BASH_REMATCH[1]}=" "$ENV_FILE" || printf '%s\n' "$line" >> "$ENV_FILE"
done < infra/local/.env.example

# TASK:CAT-03: local-only S3 credentials; keep existing values across restarts.
for s3_key in CATALOG_S3_ACCESS_KEY CATALOG_S3_SECRET_KEY; do
  if ! grep -q "^${s3_key}=." "$ENV_FILE"; then
    sed -i "s|^${s3_key}=.*|${s3_key}=$(openssl rand -hex 32)|" "$ENV_FILE"
  fi
done

# TASK:CAT-03 approved safety-net policy (quarantine lifecycle days); passed to catalog by Compose.
sed -i 's/^CATALOG_S3_QUARANTINE_RETENTION_DAYS=.*/CATALOG_S3_QUARANTINE_RETENTION_DAYS=2/' "$ENV_FILE"

# Local-only access-token signing key (TASK:USR-01a); generated once, never committed.
if ! grep -q '^USER_JWT_PRIVATE_KEY=.' "$ENV_FILE"; then
  key="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 |
    openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\r\n')"
  sed -i "s|^USER_JWT_PRIVATE_KEY=.*|USER_JWT_PRIVATE_KEY=$key|" "$ENV_FILE"
  sed -i "s|^USER_JWT_PUBLIC_KEYS=.*|USER_JWT_PUBLIC_KEYS=|" "$ENV_FILE"
fi
# Local-only AES-256 key for the reset-token handoff in Redis (TASK:USR-01b).
if ! grep -q '^USER_SECRET_KEY=.' "$ENV_FILE"; then
  sed -i "s|^USER_SECRET_KEY=.*|USER_SECRET_KEY=$(openssl rand -base64 32 | tr -d '\r\n')|" "$ENV_FILE"
fi
# Verifier key (ADR-21) derived from the private key: kid:base64-X.509.
if ! grep -q '^USER_JWT_PUBLIC_KEYS=.' "$ENV_FILE"; then
  private="$(grep '^USER_JWT_PRIVATE_KEY=' "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
  kid="$(grep '^USER_JWT_KEY_ID=' "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
  public="$(printf '%s' "$private" | base64 -d | openssl pkey -inform DER -pubout -outform DER | base64 | tr -d '\r\n')"
  sed -i "s|^USER_JWT_PUBLIC_KEYS=.*|USER_JWT_PUBLIC_KEYS=${kid:-user-local}:$public|" "$ENV_FILE"
fi

./mvnw -q -DskipTests package
npm ci
npm run build --workspace @fashion/storefront

C=(docker compose --env-file "$ENV_FILE" -f infra/local/compose.yaml)
"${C[@]}" up --wait -d postgres
# Re-apply the idempotent database/role scripts so existing volumes get databases added later.
"${C[@]}" exec -T postgres sh -c 'for f in /docker-entrypoint-initdb.d/*.sh; do bash "$f"; done' >/dev/null
"${C[@]}" up --build --wait -d
