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

# TASK:INV-01/CART-01/SHP-01/PAY-01/ORD-01/NOT-01: preserve generated inventory, cart, shipping, payment and order role credentials
# across restarts.
for inventory_key in INVENTORY_MIGRATION_DB_PASSWORD INVENTORY_DB_PASSWORD CART_MIGRATION_DB_PASSWORD CART_DB_PASSWORD \
    SHIPPING_MIGRATION_DB_PASSWORD SHIPPING_DB_PASSWORD PAYMENT_MIGRATION_DB_PASSWORD PAYMENT_DB_PASSWORD \
    ORDER_MIGRATION_DB_PASSWORD ORDER_DB_PASSWORD NOTIFICATION_MIGRATION_DB_PASSWORD NOTIFICATION_DB_PASSWORD; do
  if ! grep -q "^${inventory_key}=." "$ENV_FILE"; then
    sed -i "s|^${inventory_key}=.*|${inventory_key}=$(openssl rand -hex 32)|" "$ENV_FILE"
  fi
done

# TASK:CAT-03/CAT-02b: local-only S3 and catalog Redis credentials; keep existing values across restarts.
for s3_key in CATALOG_S3_ACCESS_KEY CATALOG_S3_SECRET_KEY CATALOG_REDIS_PASSWORD; do
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

# ADR-19 caller key pair for a service, generated once (PREFIX_SERVICE_PRIVATE/PUBLIC_KEY); its public key is merged
# into each named *_INTERNAL_CALLERS list, keeping the other callers.
service_key() {
  local prefix="$1" name="$2" key private public current others callers_key
  shift 2
  if ! grep -q "^${prefix}_SERVICE_PRIVATE_KEY=." "$ENV_FILE"; then
    key="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 |
      openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\r\n')"
    sed -i "s|^${prefix}_SERVICE_PRIVATE_KEY=.*|${prefix}_SERVICE_PRIVATE_KEY=$key|" "$ENV_FILE"
    sed -i "s|^${prefix}_SERVICE_PUBLIC_KEY=.*|${prefix}_SERVICE_PUBLIC_KEY=|" "$ENV_FILE"
  fi
  if ! grep -q "^${prefix}_SERVICE_PUBLIC_KEY=." "$ENV_FILE"; then
    private="$(grep "^${prefix}_SERVICE_PRIVATE_KEY=" "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
    public="$(printf '%s' "$private" | base64 -d | openssl pkey -inform DER -pubout -outform DER | base64 | tr -d '\r\n')"
    sed -i "s|^${prefix}_SERVICE_PUBLIC_KEY=.*|${prefix}_SERVICE_PUBLIC_KEY=$public|" "$ENV_FILE"
  fi
  public="$(grep "^${prefix}_SERVICE_PUBLIC_KEY=" "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
  for callers_key in "$@"; do
    current="$(grep "^${callers_key}=" "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
    if [[ "$current" != *"${name}:${public}"* ]]; then
      others="$(printf '%s' "$current" | tr ',' '\n' | grep -v "^${name}:" | paste -sd, - || true)"
      sed -i "s|^${callers_key}=.*|${callers_key}=${others:+$others,}${name}:${public}|" "$ENV_FILE"
    fi
  done
}
service_key CART cart-service CATALOG_INTERNAL_CALLERS INVENTORY_INTERNAL_CALLERS  # TASK:CART-01: catalog quote, inventory stock
service_key SHIPPING shipping-service CATALOG_INTERNAL_CALLERS                     # TASK:SHP-01: catalog quote for weights
service_key ORDER order-service CART_INTERNAL_CALLERS CATALOG_INTERNAL_CALLERS SHIPPING_INTERNAL_CALLERS \
  INVENTORY_INTERNAL_CALLERS PAYMENT_INTERNAL_CALLERS  # TASK:ORD-01 quote/create, ORD-02 saga

service_key NOTIFICATION notification-service USER_INTERNAL_CALLERS  # TASK:NOT-01: reset-email secret from user

# TASK:ORD-01: guest checkout token key pair (cart signs, order verifies) and the quote HMAC secret; generated once.
if ! grep -q '^CART_CHECKOUT_PRIVATE_KEY=.' "$ENV_FILE"; then
  key="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 |
    openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\r\n')"
  sed -i "s|^CART_CHECKOUT_PRIVATE_KEY=.*|CART_CHECKOUT_PRIVATE_KEY=$key|" "$ENV_FILE"
  sed -i "s|^ORDER_CHECKOUT_PUBLIC_KEYS=.*|ORDER_CHECKOUT_PUBLIC_KEYS=|" "$ENV_FILE"
fi
if ! grep -q '^ORDER_CHECKOUT_PUBLIC_KEYS=.' "$ENV_FILE"; then
  private="$(grep '^CART_CHECKOUT_PRIVATE_KEY=' "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
  kid="$(grep '^CART_CHECKOUT_KID=' "$ENV_FILE" | cut -d= -f2- | tr -d '\r')"
  public="$(printf '%s' "$private" | base64 -d | openssl pkey -inform DER -pubout -outform DER | base64 | tr -d '\r\n')"
  sed -i "s|^ORDER_CHECKOUT_PUBLIC_KEYS=.*|ORDER_CHECKOUT_PUBLIC_KEYS=${kid:-cart-checkout-1}:$public|" "$ENV_FILE"
fi
if ! grep -q '^ORDER_QUOTE_SECRET=.' "$ENV_FILE"; then
  sed -i "s|^ORDER_QUOTE_SECRET=.*|ORDER_QUOTE_SECRET=$(openssl rand -base64 32 | tr -d '\r\n')|" "$ENV_FILE"
fi

./mvnw -q -DskipTests package
npm ci
npm run build --workspace @fashion/storefront

C=(docker compose --env-file "$ENV_FILE" -f infra/local/compose.yaml)
"${C[@]}" up --wait -d postgres kafka
# Re-apply the idempotent database/role scripts so existing volumes get databases added later.
"${C[@]}" exec -T postgres sh -c 'for f in /docker-entrypoint-initdb.d/*.sh; do bash "$f"; done' >/dev/null
MSYS_NO_PATHCONV=1 "${C[@]}" exec -T kafka bash /opt/fashion-kafka-init/create-topics.sh
"${C[@]}" up --build --wait -d
