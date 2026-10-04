#!/usr/bin/env bash
# S1-local smoke: storefront (static) -> Gateway -> catalog -> PostgreSQL.
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
STOREFRONT_URL="${STOREFRONT_URL:-http://localhost:4173}"

check_page() {
  python3 -c '
import json, sys, uuid
d = json.load(sys.stdin)
assert d["code"] == "OK", d
assert isinstance(d["data"]["items"], list), d
assert d["data"]["next_cursor"] is None or isinstance(d["data"]["next_cursor"], str), d
# ProductSummary in contracts/openapi/catalog.yaml (additionalProperties: false).
for item in d["data"]["items"]:
    assert set(item) == {"id", "slug", "name_vi", "name_en"}, item
    uuid.UUID(item["id"])
    assert 1 <= len(item["slug"]) <= 160, item
    assert 1 <= len(item["name_vi"]) <= 255 and 1 <= len(item["name_en"]) <= 255, item
assert d["metadata"]["request_id"] and d["metadata"]["trace_id"], d
' <<<"$1"
}

status() { curl -sS -o /dev/null -w '%{http_code}' "$@"; }

# Readiness is guaranteed by local-up.sh (--wait on the management-port healthcheck);
# actuator must not be reachable on the public port.
test "$(status "$GATEWAY_URL/actuator/health")" = 404
check_page "$(curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/products?limit=1")"
check_page "$(curl --fail --silent --show-error "$STOREFRONT_URL/api/v1/catalog/products?limit=1")"

test "$(status -H 'X-User-Roles: SUPER_ADMIN' "$GATEWAY_URL/internal/api/v1/platform/ping")" = 404
test "$(status "$GATEWAY_URL/api/v1/catalog/products?limit=0")" = 400
# PLT-05: the W3C trace crosses Gateway -> catalog and comes back as metadata.trace_id.
TRACE_ID=4bf92f3577b34da6a3ce929d0e0e4736
curl --fail --silent --show-error -H "traceparent: 00-$TRACE_ID-00f067aa0ba902b7-01" \
  "$GATEWAY_URL/api/v1/catalog/products?limit=1" |
  python3 -c 'import json,sys; t=json.load(sys.stdin)["metadata"]["trace_id"]; assert t == sys.argv[1], t' "$TRACE_ID"

# TASK:USR-01a through the Gateway: register -> refresh (cookie + allowed Origin, rotated) ->
# replay of the old cookie rejected -> logout. Synthetic, unique account per run.
ORIGIN="${STOREFRONT_URL}"
EMAIL="smoke-$(date +%s)-$RANDOM@example.test"
cookie_of() { grep -i '^set-cookie: refresh_token=' "$1" | sed -E 's/^[^=]*=([^;]*);.*/\1/I' | tr -d '\r'; }
headers="$(mktemp)"; trap 'rm -f "$headers"' EXIT
test "$(curl -sS -o /dev/null -D "$headers" -w '%{http_code}' -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Smoke-pass-123\",\"full_name\":\"Smoke Test\",\"locale\":\"vi\"}" \
  "$GATEWAY_URL/api/v1/auth/register")" = 201
first="$(cookie_of "$headers")"
# TASK:USR-02 part 2a: Bearer access token -> /users/me and the first address becomes default.
access="$(curl -sS -H 'Content-Type: application/json' \
  -d "{\"email\":\"me-$EMAIL\",\"password\":\"Smoke-pass-123\",\"full_name\":\"Smoke Me\",\"locale\":\"en\"}" \
  "$GATEWAY_URL/api/v1/auth/register" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["access_token"])')"
curl --fail --silent --show-error -H "Authorization: Bearer $access" "$GATEWAY_URL/api/v1/users/me" |
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["email"].startswith("me-") and d["locale"] == "en", d'
curl --fail --silent --show-error -H "Authorization: Bearer $access" -H 'Content-Type: application/json' \
  -d '{"recipient_name":"Smoke","phone":"0912345678","province_code":"79","ward_code":"26734","address_line":"1 Smoke St"}' \
  "$GATEWAY_URL/api/v1/users/me/addresses" | python3 -c 'import json,sys; assert json.load(sys.stdin)["data"]["is_default"] is True'
test "$(status "$GATEWAY_URL/api/v1/users/me")" = 401
test "$(curl -sS -o /dev/null -D "$headers" -w '%{http_code}' -X POST -H "Origin: $ORIGIN" \
  -H "Cookie: refresh_token=$first" "$GATEWAY_URL/api/v1/auth/refresh")" = 200
second="$(cookie_of "$headers")"
test -n "$second" && test "$second" != "$first"
test "$(status -X POST -H "Origin: $ORIGIN" -H "Cookie: refresh_token=$first" "$GATEWAY_URL/api/v1/auth/refresh")" = 401
test "$(status -X POST -H "Origin: https://evil.example" -H "Cookie: refresh_token=$second" "$GATEWAY_URL/api/v1/auth/logout")" = 403
test "$(status -X POST -H "Origin: $ORIGIN" -H "Cookie: refresh_token=$second" "$GATEWAY_URL/api/v1/auth/logout")" = 204

test "$(status "$STOREFRONT_URL/api/not-found")" = 404
test "$(status "$STOREFRONT_URL/api")" = 404
test "$(status "$STOREFRONT_URL/assets/missing.js")" = 404
curl --fail --silent --show-error "$STOREFRONT_URL/products" | grep -q '<div id="root">'

echo "S1-local smoke PASS"
