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

curl --fail --silent --show-error "$GATEWAY_URL/actuator/health/readiness" >/dev/null
check_page "$(curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/products?limit=1")"
check_page "$(curl --fail --silent --show-error "$STOREFRONT_URL/api/v1/catalog/products?limit=1")"

test "$(status -H 'X-User-Roles: SUPER_ADMIN' "$GATEWAY_URL/internal/api/v1/platform/ping")" = 404
test "$(status "$GATEWAY_URL/api/v1/catalog/products?limit=0")" = 400
test "$(status "$STOREFRONT_URL/api/not-found")" = 404
test "$(status "$STOREFRONT_URL/api")" = 404
test "$(status "$STOREFRONT_URL/assets/missing.js")" = 404
curl --fail --silent --show-error "$STOREFRONT_URL/products" | grep -q '<div id="root">'

echo "S1-local smoke PASS"
