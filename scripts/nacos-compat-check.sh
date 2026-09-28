#!/usr/bin/env bash
# O02 proof for profile nacos-compat: config import, discovery routing, reconnect after a
# Nacos restart. Run scripts/local-up.sh first. Restores the default profile at the end.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-infra/local/.env}"
set -a; source "$ENV_FILE"; set +a
C=(docker compose --env-file "$ENV_FILE" -f infra/local/compose.yaml --profile nacos-compat)
NACOS=http://localhost:8848/nacos
PROBE=s1-nacos-compat

nacos_curl() { "${C[@]}" exec -T nacos curl -fsS "$@"; }

token() {
  nacos_curl -X POST "$NACOS/v3/auth/user/login" \
    --data-urlencode "username=$NACOS_USERNAME" --data-urlencode "password=$NACOS_PASSWORD" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'
}

registered() { # $1 = service name
  nacos_curl -G "$NACOS/v3/admin/ns/instance/list" -H "accessToken: $(token)" \
    --data-urlencode "serviceName=$1" --data-urlencode groupName=DEFAULT_GROUP |
    python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)["data"] else 1)'
}

retry() { # retry up to 60s: $@ = command
  for _ in $(seq 30); do "$@" >/dev/null 2>&1 && return 0; sleep 2; done
  "$@"
}

restore_default() {
  "${C[@]}" stop nacos
  # Without --profile, depends_on nacos (required: false) cannot block the default stack.
  APP_PROFILES= GATEWAY_CATALOG_BASE_URL= docker compose --env-file "$ENV_FILE" -f infra/local/compose.yaml \
    up -d --build --wait --force-recreate catalog gateway storefront
}
trap restore_default EXIT

"${C[@]}" up -d --wait nacos
# First-run admin initialisation; a repeat call is harmless. Response echoes the password: discard it.
nacos_curl -X POST "$NACOS/v3/auth/user/admin" --data-urlencode "password=$NACOS_PASSWORD" >/dev/null || true
nacos_curl -X POST "$NACOS/v3/admin/cs/config" -H "accessToken: $(token)" \
  --data-urlencode dataId=catalog-service.yaml --data-urlencode groupName=DEFAULT_GROUP \
  --data-urlencode type=yaml --data-urlencode "content=$(printf 'info:\n  nacos:\n    probe: %s\n' "$PROBE")" >/dev/null

APP_PROFILES=nacos-compat GATEWAY_CATALOG_BASE_URL=lb://catalog-service \
  "${C[@]}" up -d --build --wait --force-recreate catalog gateway storefront

"${C[@]}" exec -T catalog curl -fsS http://localhost:8081/actuator/info | grep -q "$PROBE"
echo "PASS config import"
registered catalog-service && registered gateway
echo "PASS discovery registration"
bash scripts/smoke-local.sh
echo "PASS Gateway -> lb://catalog-service"

"${C[@]}" restart nacos
"${C[@]}" up -d --wait nacos
retry registered catalog-service
retry registered gateway
retry bash scripts/smoke-local.sh
echo "PASS reconnect after Nacos restart"
