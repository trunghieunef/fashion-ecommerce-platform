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
# TASK:USR-02 part 2b: admin API is routed but a member without user.manage gets 403.
test "$(status -H "Authorization: Bearer $access" "$GATEWAY_URL/admin/api/v1/users")" = 403
# TASK:CAT-01a: service authorization through Gateway; this is a real member token.
test "$(status "$GATEWAY_URL/admin/api/v1/catalog/products")" = 401
test "$(status -H "Authorization: Bearer $access" "$GATEWAY_URL/admin/api/v1/catalog/products")" = 403
# Local-only synthetic OPS token; this does not prove login -> OPS token issuance.
# Node reads the local .env; key and token stay in process memory and are never logged.
catalog_access="$(node --env-file="${ENV_FILE:-infra/local/.env}" <<'NODE'
const { createPrivateKey, randomUUID, sign } = require('node:crypto');
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const header = encode({alg: 'ES256', typ: 'JWT', kid: process.env.USER_JWT_KEY_ID});
const payload = encode({iss: 'user-service', aud: 'fashion-api', sub: randomUUID(),
  auth_version: 0, permissions: ['catalog.write'], iat: now, exp: now + 300});
const key = createPrivateKey({key: Buffer.from(process.env.USER_JWT_PRIVATE_KEY, 'base64'), type: 'pkcs8', format: 'der'});
const input = `${header}.${payload}`;
process.stdout.write(`${input}.${sign('sha256', Buffer.from(input), {key, dsaEncoding: 'ieee-p1363'}).toString('base64url')}`);
NODE
)"
# No DELETE endpoint: each run leaves uniquely named fixtures on the local volume.
catalog_suffix="$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')"
catalog_post() {
  curl --fail --silent --show-error -H "Authorization: Bearer $catalog_access" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-$1" \
    -d "$3" "$GATEWAY_URL/admin/api/v1/catalog$2"
}
category_id="$(catalog_post category /categories \
  "{\"name_vi\":\"Smoke\",\"name_en\":\"Smoke\",\"slug\":\"smoke-$catalog_suffix\"}" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"] == "OK"; print(d["data"]["id"])')"
product_id="$(catalog_post product /products \
  "{\"category_id\":\"$category_id\",\"name_vi\":\"Smoke\",\"name_en\":\"Smoke\",\"slug\":\"smoke-$catalog_suffix\",\"base_price\":100000}" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] == "DRAFT" and d["version"] == 0; print(d["id"])')"
variant_id="$(catalog_post variant "/products/$product_id/variants" \
  "{\"sku\":\" smoke-$catalog_suffix \",\"size\":\"M\",\"color\":\"black\",\"weight_grams\":100}" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] == "ACTIVE" and d["version"] == 0 and d["sku"] == sys.argv[1].upper(); print(d["id"])' "SMOKE-$catalog_suffix")"
# Same key after changing casing must replay, so publish still expects product version 1.
catalog_post variant "/products/$product_id/variants" \
  "{\"sku\":\"SMOKE-${catalog_suffix^^}\",\"size\":\"M\",\"color\":\"black\",\"weight_grams\":100}" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["id"] == sys.argv[1] and d["sku"] == sys.argv[2].upper()' "$variant_id" "SMOKE-$catalog_suffix"
catalog_post publish "/products/$product_id/publish" '{"expected_version":1}' | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] == "ACTIVE" and d["version"] == 2 and d["published_at"]'
curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/products?limit=100" | \
  python3 -c 'import json,sys; assert any(p["id"] == sys.argv[1] for p in json.load(sys.stdin)["data"]["items"])' "$product_id"
# TASK:CAT-01b: retain 401 and real-member 403 before the synthetic OPS flow.
for route in /collections "/size-guides/$category_id/vi"; do
  for bearer in '' "$access"; do
    expected=401; [[ -z "$bearer" ]] || expected=403
    actual="$(status -H "Authorization: Bearer $bearer" "$GATEWAY_URL/admin/api/v1/catalog$route")"
    if [[ "$actual" != "$expected" ]]; then
      echo "CAT-01b $route: expected $expected, got $actual" >&2; exit 1
    fi
  done
done
# Capture headers/body in process memory, checking status and metadata on every call.
catalog_request() {
  local expected="$1" method="$2" route="$3" body="${4-}" key="${5-}"
  local args=(-sS -i -X "$method" -H "Authorization: Bearer $catalog_access")
  # Windows native curl argv uses the ANSI codepage; stdin preserves UTF-8 table text.
  [[ -z "$body" ]] || args+=(-H 'Content-Type: application/json' --data-binary @-)
  [[ -z "$key" ]] || args+=(-H "Idempotency-Key: $key")
  printf '%s' "$body" | curl "${args[@]}" "$GATEWAY_URL/admin/api/v1/catalog$route" | MSYS_NO_PATHCONV=1 python3 -c '
import json,re,sys,uuid
head,body=sys.stdin.buffer.read().decode("utf-8").replace("\r\n","\n").split("\n\n",1)
code=int(head.splitlines()[0].split()[1]); d=json.loads(body)
assert code == int(sys.argv[1]), (sys.argv[2],code,sys.argv[1],d.get("code"),d.get("message"),d.get("errors"))
headers=dict(line.lower().split(":",1) for line in head.splitlines()[1:] if ":" in line)
m=d["metadata"]; uuid.UUID(m["request_id"])
assert re.fullmatch("[0-9a-f]{32}",m["trace_id"])
assert headers["x-correlation-id"].strip() == m["trace_id"]
assert d["code"] == ("OK" if code < 400 else "VERSION_CONFLICT")
print(json.dumps(d))' "$expected" "$route"
}
collection_body="{\"name_vi\":\"Smoke\",\"name_en\":\"Smoke\",\"slug\":\"smoke-$catalog_suffix\",\"items\":[{\"product_id\":\"$product_id\",\"sort_order\":0}]}"
collection="$(catalog_request 201 POST /collections "$collection_body" "smoke-$catalog_suffix-collection")"
collection_id="$(python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] == "DRAFT" and d["version"] == 0 and d["cover_url"] is None and d["items"] == [{"product_id":sys.argv[1],"sort_order":0}]; print(d["id"])' "$product_id" <<<"$collection")"
collection_read="$(catalog_request 200 GET "/collections/$collection_id")"
python3 -c 'import json,sys; assert json.loads(sys.argv[1])["data"] == json.load(sys.stdin)["data"]' "$collection" <<<"$collection_read"
collection_version="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["version"])' <<<"$collection_read")"
collection_update="{\"name_vi\":\"Smoke\",\"name_en\":\"Smoke\",\"slug\":\"smoke-$catalog_suffix\",\"status\":\"ACTIVE\",\"expected_version\":$collection_version,\"items\":[{\"product_id\":\"$product_id\",\"sort_order\":7}]}"
# PUT collection intentionally has no Idempotency-Key.
catalog_request 200 PUT "/collections/$collection_id" "$collection_update" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] == "ACTIVE" and d["version"] == 1 and d["items"] == [{"product_id":sys.argv[1],"sort_order":7}]' "$product_id"
catalog_request 409 PUT "/collections/$collection_id" "$collection_update" >/dev/null
echo 'CAT-01b collection create/read/update/stale PASS'
guide_path="/size-guides/$category_id/vi"
guide_body='{"expected_version":0,"table_json":{"columns":["Size","Chest (cm)"],"rows":[["M","96–100"]]}}'
guide="$(catalog_request 201 PUT "$guide_path" "$guide_body" "smoke-$catalog_suffix-guide")"
python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["version"] == 1 and d["guideline_html"] == "" and d["category_id"] == sys.argv[1] and d["locale"] == "vi" and d["table_json"] == {"columns":["Size","Chest (cm)"],"rows":[["M","96–100"]]}, ("guide create",d)' "$category_id" <<<"$guide"
guide_read="$(catalog_request 200 GET "$guide_path")"
python3 -c 'import json,sys; assert json.loads(sys.argv[1])["data"] == json.load(sys.stdin)["data"]' "$guide" <<<"$guide_read"
echo 'CAT-01b size guide create/read PASS'
guide_version="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["version"])' <<<"$guide_read")"
guide_update="{\"expected_version\":$guide_version,\"guideline_html\":\"<p>Smoke size guide</p>\",\"table_json\":{\"columns\":[\"Size\",\"Chest (cm)\"],\"rows\":[[\"M\",\"96–100\"]]}}"
catalog_request 200 PUT "$guide_path" "$guide_update" "smoke-$catalog_suffix-guide-update" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["version"] == 2 and d["guideline_html"] == "<p>Smoke size guide</p>", ("guide update",d)'
catalog_request 409 PUT "$guide_path" "$guide_update" "smoke-$catalog_suffix-guide-stale" >/dev/null
guide_replay="$(catalog_request 201 PUT "$guide_path" "$guide_body" "smoke-$catalog_suffix-guide")"
python3 -c 'import json,sys; a=json.loads(sys.argv[1]); b=json.load(sys.stdin); assert a["data"] == b["data"] and a["metadata"]["request_id"] != b["metadata"]["request_id"]' "$guide" <<<"$guide_replay"
echo 'CAT-01b size guide update/stale/replay PASS'
unset catalog_access
# TASK:USR-01 part 1b-i: forgot answers 202 for known and unknown e-mails (Redis handoff in
# Compose); change password revokes the current access token.
for target in "me-$EMAIL" "nobody-$EMAIL"; do
  test "$(status -X POST -H 'Content-Type: application/json' -d "{\"email\":\"$target\"}" \
    "$GATEWAY_URL/api/v1/auth/password/forgot")" = 202
done
change='{"current_password":"Smoke-pass-123","new_password":"Smoke-pass-456"}' # gitleaks:allow synthetic smoke passwords
test "$(status -X POST -H "Authorization: Bearer $access" -H 'Content-Type: application/json' \
  -d "$change" "$GATEWAY_URL/api/v1/auth/password/change")" = 204
test "$(status -H "Authorization: Bearer $access" "$GATEWAY_URL/api/v1/users/me")" = 401
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
