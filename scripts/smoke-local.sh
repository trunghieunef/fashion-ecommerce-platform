#!/usr/bin/env bash
# S1-local smoke: storefront (static) -> Gateway -> catalog -> PostgreSQL.
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
STOREFRONT_URL="${STOREFRONT_URL:-http://localhost:4173}"
C=(docker compose --env-file "${ENV_FILE:-infra/local/.env}" -f infra/local/compose.yaml)

check_page() {
  python3 -c '
import json, sys, uuid
d = json.load(sys.stdin)
assert d["code"] == "OK", d
assert isinstance(d["data"]["items"], list), d
assert d["data"]["next_cursor"] is None or isinstance(d["data"]["next_cursor"], str), d
# ProductSummary in contracts/openapi/catalog.yaml (additionalProperties: false).
for item in d["data"]["items"]:
    assert set(item) == {"id", "slug", "name_vi", "name_en", "min_price", "max_price", "image"}, item
    assert isinstance(item["min_price"], int) and 0 <= item["min_price"] <= item["max_price"], item
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
local_ops_token() {
node --env-file="${ENV_FILE:-infra/local/.env}" - "$@" <<'NODE'
const { createPrivateKey, randomUUID, sign } = require('node:crypto');
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const header = encode({alg: 'ES256', typ: 'JWT', kid: process.env.USER_JWT_KEY_ID});
const payload = encode({iss: 'user-service', aud: 'fashion-api', sub: randomUUID(),
  auth_version: 0, permissions: process.argv.slice(2), iat: now, exp: now + 300});
const key = createPrivateKey({key: Buffer.from(process.env.USER_JWT_PRIVATE_KEY, 'base64'), type: 'pkcs8', format: 'der'});
const input = `${header}.${payload}`;
process.stdout.write(`${input}.${sign('sha256', Buffer.from(input), {key, dsaEncoding: 'ieee-p1363'}).toString('base64url')}`);
NODE
}
catalog_access="$(local_ops_token catalog.write)"
inventory_access="$(local_ops_token inventory.read inventory.adjust)"
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
read -r variant_id variant_trace < <(catalog_post variant "/products/$product_id/variants" \
  "{\"sku\":\" smoke-$catalog_suffix \",\"size\":\"M\",\"color\":\"black\",\"weight_grams\":100}" | \
  python3 -c 'import json,sys; r=json.load(sys.stdin); d=r["data"]; assert d["status"] == "ACTIVE" and d["version"] == 0 and d["sku"] == sys.argv[1].upper(); print(d["id"], r["metadata"]["trace_id"])' "SMOKE-$catalog_suffix")
variant_trace="${variant_trace//[^0-9a-f]/}"  # Windows python prints CRLF and read keeps the CR: keep hex only
test -n "$variant_id" && test -n "$variant_trace"
# Same key after changing casing must replay, so publish still expects product version 1.
inventory_sku="SMOKE-${catalog_suffix^^}"
inventory_seen=false
if INVENTORY_SMOKE_TOKEN="$inventory_access" node --input-type=module - "$GATEWAY_URL" "$inventory_sku" <<'NODE'
import {setTimeout as delay} from 'node:timers/promises';
const [base, sku] = process.argv.slice(2);
const deadline = performance.now() + 10000;
let seen = false;
while (performance.now() < deadline) {
  try {
    const response = await fetch(`${base}/admin/api/v1/inventory/skus?sku_prefix=${encodeURIComponent(sku)}`, {
      headers: {Authorization: `Bearer ${process.env.INVENTORY_SMOKE_TOKEN}`},
      signal: AbortSignal.timeout(Math.max(1, Math.ceil(deadline - performance.now())))
    });
    if (response.ok && (await response.json()).data.items.some(item => item.sku === sku)
        && performance.now() < deadline) { seen = true; break; }
  } catch { /* Retry transient startup failures within the same deadline. */ }
  const left = deadline - performance.now();
  if (left > 0) await delay(Math.min(250, left));
}
if (!seen) console.error('Inventory SKU registration deadline exceeded');
process.exitCode = seen ? 0 : 1;
NODE
then inventory_seen=true
fi
test "$inventory_seen" = true
curl --fail --silent --show-error -H "Authorization: Bearer $inventory_access" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-adjust" \
  -d "{\"sku\":\"$inventory_sku\",\"delta\":5,\"reason\":\"smoke\"}" \
  "$GATEWAY_URL/admin/api/v1/inventory/adjust" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["available"]==5 and d["version"]==1'
curl --fail --silent --show-error -H "Authorization: Bearer $inventory_access" \
  "$GATEWAY_URL/admin/api/v1/inventory/skus?sku_prefix=$inventory_sku" | \
  python3 -c 'import json,sys; items=json.load(sys.stdin)["data"]["items"]; assert len(items)==1; d=items[0]; assert d["sku"]==sys.argv[1] and d["available"]==5 and d["version"]==1' "$inventory_sku"
# Console consumer exits 1 on idle timeout; the scan must still find the adjusted snapshot.
set +e
MSYS_NO_PATHCONV=1 "${C[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic inventory.events --from-beginning --timeout-ms 10000 2>/dev/null | python3 -c '
import json,sys
found=False
for line in sys.stdin:
    e=json.loads(line)
    if e["event_type"]=="INVENTORY_UPDATED" and e["payload"]["sku"]==sys.argv[1] and e["payload"]["available"]==5:
        found=True
assert found, "inventory adjusted snapshot missing"' "$inventory_sku"
inventory_codes=("${PIPESTATUS[@]}")
set -e
test "${inventory_codes[1]}" = 0
[[ "${inventory_codes[0]}" = 0 || "${inventory_codes[0]}" = 1 ]]
# TASK:PLT-05: the variant request is one trace in Tempo across Gateway -> catalog -> Kafka -> inventory
# -> Kafka -> catalog. Ingestion is asynchronous, so poll; a stopped Tempo fails only this step.
TEMPO_URL="${TEMPO_URL:-http://127.0.0.1:3200}"
trace_ok=1
for _ in $(seq 1 30); do
  if curl -fsS "$TEMPO_URL/api/v2/traces/$variant_trace" 2>/dev/null | python3 -c '
import json,sys
spans=[]
for rs in json.load(sys.stdin)["trace"]["resourceSpans"]:
    service=next(a["value"]["stringValue"] for a in rs["resource"]["attributes"] if a["key"]=="service.name")
    for ss in rs["scopeSpans"]:
        spans += [(service, s["name"]) for s in ss["spans"]]
services={s for s,_ in spans}; names={n for _,n in spans}
assert {"gateway","catalog-service","inventory-service"} <= services, services
assert "outbox publish VARIANT_CREATED" in names and "outbox publish INVENTORY_UPDATED" in names, names
assert any(s=="catalog-service" and "inventory.events" in n for s,n in spans), spans' 2>/dev/null; then trace_ok=0; break; fi
  sleep 1
done
if [[ "$trace_ok" != 0 ]]; then
  echo "PLT-05 Kafka trace FAIL: trace $(printf '%q' "$variant_trace") incomplete or Tempo unreachable at $TEMPO_URL" >&2
  curl -sS "$TEMPO_URL/api/v2/traces/$variant_trace" 2>&1 | head -c 300 >&2; echo >&2; exit 1
fi
echo "PLT-05 Kafka trace PASS (trace_id=$variant_trace)"
catalog_post variant "/products/$product_id/variants" \
  "{\"sku\":\"SMOKE-${catalog_suffix^^}\",\"size\":\"M\",\"color\":\"black\",\"weight_grams\":100}" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["id"] == sys.argv[1] and d["sku"] == sys.argv[2].upper()' "$variant_id" "SMOKE-$catalog_suffix"
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
# TASK:CAT-03: upload -> PUT to RustFS -> complete -> attach -> publish -> public read -> unpublish.
# The signed URL and token live only in process memory and are never printed.
mkdir -p .superpowers/cat-03
png="$(mktemp -p .superpowers/cat-03 smoke-XXXXXX.png)"
python3 - "$png" <<'PY'
import struct, sys, zlib
w = h = 64
raw = b"".join(bytes([0]) + bytes(v for x in range(w) for v in (x * 4, y * 4, 128)) for y in range(h))
chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
open(sys.argv[1], "wb").write(bytes([137]) + b"PNG" + bytes([13, 10, 26, 10]) + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                              + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
PY
png_size="$(wc -c <"$png" | tr -d ' ')"
intent="$(catalog_request 201 POST /images/uploads   "{\"filename\":\"smoke.png\",\"content_type\":\"image/png\",\"size_bytes\":$png_size,\"target_type\":\"PRODUCT\",\"target_id\":\"$product_id\"}"   "smoke-$catalog_suffix-intent")"
upload_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["upload_id"])' <<<"$intent")"
python3 -c '
import json, sys, urllib.request
d = json.load(sys.stdin)["data"]
req = urllib.request.Request(d["put_url"], data=open(sys.argv[1], "rb").read(), method="PUT",
                             headers={k: str(v) for k, v in d["put_headers"].items()})
assert urllib.request.urlopen(req, timeout=20).status == 200' "$png" <<<"$intent"
rm -f "$png"
asset_id="$(catalog_request 200 POST "/images/uploads/$upload_id/complete" |   python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; a=d.get("asset") or d; assert a["image"]["content_type"] == "image/png" and a["thumb"]["width"] <= 800, d; print(a["asset_id"])')"
catalog_request 200 GET "/images/uploads/$upload_id" |   python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["state"] == "APPROVED" and d["asset_availability"] is not None, d'
catalog_request 200 PUT "/products/$product_id/images"   "{\"expected_version\":1,\"images\":[{\"asset_id\":\"$asset_id\",\"alt_vi\":\"Smoke\",\"alt_en\":\"Smoke\",\"sort_order\":0}]}" |   python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["version"] == 2 and len(d["images"]) == 1, d'
image_url="$GATEWAY_URL/api/v1/catalog/images/$asset_id?kind=image"
test "$(status "$image_url")" = 404 # attached but the product is still DRAFT
echo 'CAT-03 upload/complete/attach PASS'
catalog_request 200 POST "/products/$product_id/publish" '{"expected_version":2}' "smoke-$catalog_suffix-publish" |   python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] == "ACTIVE" and d["version"] == 3 and d["published_at"]'
curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/products?limit=100" |   python3 -c 'import json,sys; assert any(p["id"] == sys.argv[1] for p in json.load(sys.stdin)["data"]["items"])' "$product_id"
# TASK:CAT-02a: browse the published fixture, read ACTIVE variants, then search its accent-free name.
curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/products?size=M&limit=100" | \
  python3 -c 'import json,sys; assert any(p["id"] == sys.argv[1] for p in json.load(sys.stdin)["data"]["items"])' "$product_id"
curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/products/$product_id" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["id"] == sys.argv[1] and any(v["id"] == sys.argv[2] for v in d["variants"])' "$product_id" "$variant_id"
curl --fail --silent --show-error "$GATEWAY_URL/api/v1/catalog/search?q=Smoke&limit=100" | \
  python3 -c 'import json,sys; assert any(p["id"] == sys.argv[1] for p in json.load(sys.stdin)["data"]["items"])' "$product_id"
echo 'CAT-02a browse/detail/search PASS'
# TASK:CART-01: guest cart via Gateway (cookie + allowed Origin), add the published smoke variant
# (inventory available 5), retry with the same key adds once, read preview; member opens a cart.
cart_jar="$(mktemp)"
cart_id="$(curl -sS -c "$cart_jar" -X POST "$GATEWAY_URL/api/v1/carts" | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["owner"]=="GUEST" and d["version"]==0; print(d["cart_id"])')"
grep -q 'cart_token' "$cart_jar"
cart_add() {
  curl -sS -b "$cart_jar" -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-$catalog_suffix-cart" \
    -d "{\"variant_id\":\"$variant_id\",\"quantity\":2,\"expected_version\":0}" "$GATEWAY_URL/api/v1/carts/$cart_id/items"
}
cart_add | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["version"]==1 and d["items"][0]["quantity"]==2, d'
cart_add | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["version"]==1 and d["items"][0]["quantity"]==2, d'
curl -sS -b "$cart_jar" "$GATEWAY_URL/api/v1/carts/$cart_id" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["preview_status"]=="OK", d; p=d["items"][0]["preview"]; assert p["available"]==5 and p["active"], p'
test "$(status "$GATEWAY_URL/api/v1/carts/$cart_id")" = 404
curl -sS -X POST -H "Authorization: Bearer $access" "$GATEWAY_URL/api/v1/carts" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["owner"]=="MEMBER", d'
rm -f "$cart_jar"
echo 'CART-01 guest/member cart PASS'
# TASK:SHP-01: estimate through the Gateway; shipping reads weight from catalog with its own service token.
curl -sS -X POST -H 'Content-Type: application/json' -H 'Origin: http://localhost:4173'   -d "{\"province_code\":\"79\",\"cod\":true,\"items\":[{\"variant_id\":\"$variant_id\",\"quantity\":2}]}"   "$GATEWAY_URL/api/v1/shipping/fee/estimate" |   python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["carrier"]=="SELF" and d["weight_grams"]==200 and d["fee"]>0 and d["estimate"], d'
test "$(status -X POST -H 'Content-Type: application/json' -H 'Origin: http://localhost:4173'   -d '{"province_code":"79","cod":false,"items":[{"variant_id":"00000000-0000-4000-8000-000000000000","quantity":1}]}'   "$GATEWAY_URL/api/v1/shipping/fee/estimate")" = 422
test "$(status -X POST "$GATEWAY_URL/internal/api/v1/shipping/quotes")" = 404
echo 'SHP-01 shipping estimate PASS'
# TASK:ORD-01: guest checkout through the Gateway: cart checkout token -> server quote (SELF fee of province 79) ->
# order 202 PENDING with the order_access cookie -> read it with that cookie only.
order_jar="$(mktemp)"
order_cart="$(curl -sS -c "$order_jar" -X POST "$GATEWAY_URL/api/v1/carts" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["cart_id"])')"
curl -sS -b "$order_jar" -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-order-cart" \
  -d "{\"variant_id\":\"$variant_id\",\"quantity\":1,\"expected_version\":0}" "$GATEWAY_URL/api/v1/carts/$order_cart/items" >/dev/null
checkout="$(curl -sS -b "$order_jar" -X POST -H "Origin: $STOREFRONT_URL" "$GATEWAY_URL/api/v1/carts/$order_cart/checkout-token" | \
  python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["checkout_token"])')"
quote_token="$(curl -sS -X POST -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' -H "Checkout-Token: $checkout" \
  -d "{\"cart_id\":\"$order_cart\",\"cart_version\":1,\"method\":\"COD\",\"shipping_address\":{\"recipient_name\":\"Smoke\",\"phone\":\"0912345678\",\"province_code\":\"79\",\"ward_code\":\"26734\",\"address_line\":\"1 Smoke St\"},\"contact\":{\"phone\":\"0912345678\",\"email\":\"smoke@example.test\"}}" \
  "$GATEWAY_URL/api/v1/orders/quote" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; t=d["totals"]; assert t["shipping_fee"]>0 and t["total_amount"]==t["subtotal"]+t["shipping_fee"]+t["cod_fee"], d; print(d["quote_token"])')"
order_no="$(curl -sS -b "$order_jar" -c "$order_jar" -X POST -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' \
  -H "Checkout-Token: $checkout" -H "Idempotency-Key: smoke-$catalog_suffix-order" \
  -d "{\"cart_id\":\"$order_cart\",\"cart_version\":1,\"quote_token\":\"$quote_token\"}" "$GATEWAY_URL/api/v1/orders" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"]=="PENDING" and d["processing_status"]=="PROCESSING", d; print(d["order_no"])')"
grep -q 'order_access' "$order_jar"
curl -sS -b "$order_jar" "$GATEWAY_URL/api/v1/orders/$order_no" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["order_no"]==sys.argv[1] and d["items"][0]["quantity"]==1, d' "$order_no"
test "$(status "$GATEWAY_URL/api/v1/orders/$order_no")" = 404
echo 'ORD-01 guest quote/create/read PASS'
# TASK:ORD-02: the saga reserves the stock (inventory) and creates the COD payment (payment), then the order is CONFIRMED.
for _ in $(seq 1 30); do
  order_status="$(curl -sS -b "$order_jar" "$GATEWAY_URL/api/v1/orders/$order_no" | \
    python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d["status"] + " " + d["processing_status"])')"
  [[ "$order_status" == CONFIRMED* || "$order_status" == CANCELLED* ]] && break
  sleep 1
done
test "$order_status" = "CONFIRMED READY" || { echo "ORD-02 saga FAIL: $order_status" >&2; exit 1; }
rm -f "$order_jar"
echo 'ORD-02 saga confirm PASS'
# TASK:SHP-02: OPS self-events go through the Gateway and need shipping.self_event; internal cancel is never routed.
# No shipment exists without order-service, so an unknown id answering 404 proves route + permission, not a transition.
shipping_ops="$(local_ops_token shipping.self_event)"
self_event_url="$GATEWAY_URL/admin/api/v1/shipping/shipments/00000000-0000-4000-8000-000000000000/self-events"
self_event='{"event_type":"ASSIGN","expected_version":1,"assigned_to":"11111111-1111-4111-8111-111111111111"}'
test "$(status -X POST -H "Authorization: Bearer $shipping_ops" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-shp02' -d "$self_event" "$self_event_url")" = 404
test "$(status -X POST -H "Authorization: Bearer $access" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-shp02' -d "$self_event" "$self_event_url")" = 403
test "$(status -X POST "$GATEWAY_URL/internal/api/v1/shipping/shipments/by-order/00000000-0000-4000-8000-000000000000/cancel")" = 404
echo 'SHP-02 self-event route PASS'
# TASK:PAY-01: FINANCE payment admin through the Gateway (payment.read); internal create/close are never routed.
# No payment exists without order-service, so the list answers and an unknown id is 404.
finance="$(local_ops_token payment.read)"
curl -sS -H "Authorization: Bearer $finance" "$GATEWAY_URL/admin/api/v1/payments?limit=1" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert "items" in d and "next_cursor" in d, d'
test "$(status -H "Authorization: Bearer $finance" "$GATEWAY_URL/admin/api/v1/payments/00000000-0000-4000-8000-000000000000")" = 404
test "$(status -H "Authorization: Bearer $access" "$GATEWAY_URL/admin/api/v1/payments")" = 403
test "$(status -X POST "$GATEWAY_URL/internal/api/v1/payments")" = 404
echo 'PAY-01 payment admin route PASS'
# TASK:ORD-03: the ORD-02 order end to end through the Gateway: OPS confirm-cod -> inventory commit -> PACKING + SELF
# shipment -> shipper self-events -> DELIVERED -> FINANCE settlement -> payment SUCCESS -> order paid (payment_state PAID
# is written with orders.paid_at in one transaction, ORD-02 PaymentEvents).
ops="$(local_ops_token order.read order.fulfill shipping.read shipping.self_event)"
finance="$(local_ops_token order.read payment.read payment.settle)"
admin_order() {
  curl -sS -H "Authorization: Bearer $1" "$GATEWAY_URL/admin/api/v1/orders/$order_no" | \
    python3 -c "import json,sys; d=json.load(sys.stdin)['data']; s=d['saga'] or {}; print($2)"
}
test "$(admin_order "$finance" "'contact_phone' in d or 'shipping_address' in d")" = False
order_version="$(admin_order "$ops" "d['version']")"
test "$(status -X POST -H "Authorization: Bearer $finance" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-confirm" \
  -d "{\"expected_version\":$order_version}" "$GATEWAY_URL/admin/api/v1/orders/$order_no/confirm-cod")" = 403
curl -sS -H "Authorization: Bearer $ops" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-confirm" \
  -d "{\"expected_version\":$order_version,\"note\":\"Smoke: khach dong y\"}" "$GATEWAY_URL/admin/api/v1/orders/$order_no/confirm-cod" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"]=="CONFIRMED" and d["processing_status"]=="PROCESSING", d'
for _ in $(seq 1 20); do
  order_status="$(admin_order "$ops" "d['status'] + ' ' + str(s.get('status')) + ' ' + str(s.get('shipment_id'))")"
  [[ "$order_status" == PACKING\ DONE\ * || "$order_status" == CANCELLED* || "$order_status" == *MANUAL* ]] && break
  sleep 1
done
[[ "$order_status" == PACKING\ DONE\ * ]] || { echo "ORD-03 commit/shipment FAIL: $order_status" >&2; exit 1; }
shipment_id="${order_status##* }"
self_event() {
  curl -sS -H "Authorization: Bearer $ops" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-$1" \
    -d "$2" "$GATEWAY_URL/admin/api/v1/shipping/shipments/$shipment_id/self-events" | \
    python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["code"]=="OK", r; print(r["data"]["version"])'
}
shipment_version="$(curl -sS -H "Authorization: Bearer $ops" "$GATEWAY_URL/admin/api/v1/shipping/shipments/$shipment_id" | \
  python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["version"])')"
cod_amount="$(admin_order "$ops" "d['total_amount']")"
shipment_version="$(self_event assign "{\"event_type\":\"ASSIGN\",\"expected_version\":$shipment_version,\"assigned_to\":\"11111111-1111-4111-8111-111111111111\"}")"
shipment_version="$(self_event handover "{\"event_type\":\"HANDED_OVER\",\"expected_version\":$shipment_version,\"evidence_ref\":\"smoke-handover\"}")"
shipment_version="$(self_event delivered "{\"event_type\":\"DELIVERED\",\"expected_version\":$shipment_version,\"evidence_ref\":\"smoke-pod\"}")"
self_event cod "{\"event_type\":\"COD_COLLECTED\",\"expected_version\":$shipment_version,\"amount\":$cod_amount,\"evidence_ref\":\"smoke-cod\"}" >/dev/null
for _ in $(seq 1 20); do
  order_status="$(admin_order "$ops" "d['status'] + ' ' + str(d['shipped_at'] is not None)")"
  [[ "$order_status" == DELIVERED* ]] && break
  sleep 1
done
test "$order_status" = "DELIVERED True" || { echo "ORD-03 delivery FAIL: $order_status" >&2; exit 1; }
payment_id="$(admin_order "$ops" "s['payment_id']")"
for _ in $(seq 1 20); do
  read -r payment_status payment_version < <(curl -sS -H "Authorization: Bearer $finance" "$GATEWAY_URL/admin/api/v1/payments/$payment_id" | \
    python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d["status"], d["version"])')
  [ "$payment_status" = COD_COLLECTED ] && break
  sleep 1
done
test "$payment_status" = COD_COLLECTED || { echo "ORD-03 COD evidence FAIL: $payment_status" >&2; exit 1; }
curl -sS -H "Authorization: Bearer $finance" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-settle" \
  -d "{\"settlement_ref\":\"SMOKE-$catalog_suffix\",\"gross_amount\":$cod_amount,\"fee_amount\":0,\"net_amount\":$cod_amount,\"evidence_ref\":\"smoke-statement\",\"expected_version\":$payment_version}" \
  "$GATEWAY_URL/admin/api/v1/payments/$payment_id/cod-settlements" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"]=="SUCCESS" and d["paid_at"], d'
for _ in $(seq 1 20); do
  order_status="$(admin_order "$ops" "s['payment_state']")"
  [ "$order_status" = PAID ] && break
  sleep 1
done
test "$order_status" = PAID || { echo "ORD-03 paid FAIL: $order_status" >&2; exit 1; }
echo 'ORD-03 COD end-to-end PASS'
# TASK:ORD-04: cancel after confirm, shipment come-back with a counted return slip, and the customer's own cancel, through the
# Gateway. Each scenario checks the SKU's available stock against the value before the order: stock must come back exactly.
ord04_token() { local_ops_token order.read order.fulfill order.cancel order.return shipping.read shipping.self_event; }
ord04_available() {
  set -e
  curl -sS -H "Authorization: Bearer $(local_ops_token inventory.read)" "$GATEWAY_URL/admin/api/v1/inventory/skus?sku_prefix=$inventory_sku" | \
    python3 -c 'import json,sys; items=json.load(sys.stdin)["data"]["items"]; assert len(items)==1, items; print(items[0]["available"])'
}
ord04_admin() { # token order_no python-expression over d (order) and s (saga)
  set -e
  curl -sS -H "Authorization: Bearer $1" "$GATEWAY_URL/admin/api/v1/orders/$2" | \
    python3 -c "import json,sys; d=json.load(sys.stdin)['data']; s=d['saga'] or {}; print($3)"
}
ord04_post() { # token path key body
  curl -sS -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$4" "$GATEWAY_URL$2"
}
ord04_wait() { # token order_no expression glob-pattern: polls up to 40 s, prints the last value
  local v=""
  for _ in $(seq 1 40); do
    v="$(ord04_admin "$1" "$2" "$3")"
    [[ "$v" == $4 ]] && break
    sleep 1
  done
  printf '%s' "$v"
}
ord04_new_order() { # cookie-jar tag quantity: a guest COD order, prints order_no
  set -e
  local jar="$1" tag="$2" qty="$3" cart token quote
  cart="$(curl -sS -c "$jar" -X POST "$GATEWAY_URL/api/v1/carts" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["cart_id"])')"
  curl -sS -b "$jar" -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-ord04-$tag-cart" \
    -d "{\"variant_id\":\"$variant_id\",\"quantity\":$qty,\"expected_version\":0}" "$GATEWAY_URL/api/v1/carts/$cart/items" >/dev/null
  token="$(curl -sS -b "$jar" -X POST -H "Origin: $STOREFRONT_URL" "$GATEWAY_URL/api/v1/carts/$cart/checkout-token" | \
    python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["checkout_token"])')"
  quote="$(curl -sS -X POST -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' -H "Checkout-Token: $token" \
    -d "{\"cart_id\":\"$cart\",\"cart_version\":1,\"method\":\"COD\",\"shipping_address\":{\"recipient_name\":\"Smoke\",\"phone\":\"0912345678\",\"province_code\":\"79\",\"ward_code\":\"26734\",\"address_line\":\"1 Smoke St\"},\"contact\":{\"phone\":\"0912345678\",\"email\":\"smoke@example.test\"}}" \
    "$GATEWAY_URL/api/v1/orders/quote" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["quote_token"])')"
  curl -sS -b "$jar" -c "$jar" -X POST -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' \
    -H "Checkout-Token: $token" -H "Idempotency-Key: smoke-$catalog_suffix-ord04-$tag-order" \
    -d "{\"cart_id\":\"$cart\",\"cart_version\":1,\"quote_token\":\"$quote\"}" "$GATEWAY_URL/api/v1/orders" | \
    python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"]=="PENDING", d; print(d["order_no"])'
}
ord04_fail() { echo "ORD-04 $1 FAIL: $2" >&2; exit 1; }
ord04_confirm_to_packing() { # token order_no tag: OPS confirm-cod, then waits for PACKING + the SELF shipment
  local version
  version="$(ord04_admin "$1" "$2" "d['version']")"
  ord04_post "$1" "/admin/api/v1/orders/$2/confirm-cod" "smoke-$catalog_suffix-ord04-$3-confirm" "{\"expected_version\":$version}" | \
    python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]=="OK" and d["data"]["status"]=="CONFIRMED", d'
  [[ "$(ord04_wait "$1" "$2" "d['status'] + ' ' + str(s.get('status'))" 'PACKING DONE')" == 'PACKING DONE' ]]
}

# (A) New order -> OPS confirm -> PACKING (SELF shipment CREATED) -> OPS cancel: shipment cancelled first, committed stock restored.
stock_a="$(ord04_available)"
jar_a="$(mktemp)"
order_a="$(ord04_new_order "$jar_a" a 1)"
ops="$(ord04_token)"
[[ "$(ord04_wait "$ops" "$order_a" "d['status'] + ' ' + str(s.get('step'))" 'CONFIRMED WAIT_OPS')" == 'CONFIRMED WAIT_OPS' ]] || ord04_fail 'cancel after confirm' 'order A did not reach WAIT_OPS'
ord04_confirm_to_packing "$ops" "$order_a" a || ord04_fail 'cancel after confirm' 'order A did not reach PACKING'
version="$(ord04_admin "$ops" "$order_a" "d['version']")"
ord04_post "$ops" "/admin/api/v1/orders/$order_a/cancel" "smoke-$catalog_suffix-ord04-a-cancel" "{\"expected_version\":$version,\"reason\":\"Smoke: het hang khi dong goi\"}" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]=="OK" and d["data"]["status"]=="CANCELLING", d'
result="$(ord04_wait "$ops" "$order_a" "d['status'] + ' ' + str(s.get('status')) + ' ' + str(s.get('inventory_state')) + ' ' + str(d['cancel_reason'])" 'CANCELLED DONE *')"
test "$result" = 'CANCELLED DONE RESTORED OPS_CANCELLED' || ord04_fail 'cancel after confirm' "order A: $result"
test "$(ord04_available)" = "$stock_a" || ord04_fail 'cancel after confirm' "stock $(ord04_available) != $stock_a"
rm -f "$jar_a"
echo 'ORD-04 cancel after confirm PASS'

# (B) New order (2 items) -> confirm -> PACKING -> shipper hands over, delivery fails, shipment comes back -> RETURNING ->
# OPS counts 2 received, 1 restocked -> inventory +1 once -> payment closed -> RETURNED.
stock_b="$(ord04_available)"
jar_b="$(mktemp)"
order_b="$(ord04_new_order "$jar_b" b 2)"
ops="$(ord04_token)"
[[ "$(ord04_wait "$ops" "$order_b" "d['status'] + ' ' + str(s.get('step'))" 'CONFIRMED WAIT_OPS')" == 'CONFIRMED WAIT_OPS' ]] || ord04_fail return 'order B did not reach WAIT_OPS'
ord04_confirm_to_packing "$ops" "$order_b" b || ord04_fail return 'order B did not reach PACKING'
shipment_id="$(ord04_admin "$ops" "$order_b" "s['shipment_id']")"
shipment_version="$(curl -sS -H "Authorization: Bearer $ops" "$GATEWAY_URL/admin/api/v1/shipping/shipments/$shipment_id" | \
  python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["version"])')"
ord04_event() {
  ord04_post "$ops" "/admin/api/v1/shipping/shipments/$shipment_id/self-events" "smoke-$catalog_suffix-ord04-b-$1" "$2" | \
    python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["code"]=="OK", r; print(r["data"]["version"])'
}
shipment_version="$(ord04_event assign "{\"event_type\":\"ASSIGN\",\"expected_version\":$shipment_version,\"assigned_to\":\"11111111-1111-4111-8111-111111111111\"}")"
shipment_version="$(ord04_event handover "{\"event_type\":\"HANDED_OVER\",\"expected_version\":$shipment_version,\"evidence_ref\":\"smoke-handover\"}")"
shipment_version="$(ord04_event failed "{\"event_type\":\"DELIVERY_FAILED\",\"expected_version\":$shipment_version,\"reason\":\"Smoke: khach vang nha\"}")"
shipment_version="$(ord04_event start "{\"event_type\":\"RETURN_STARTED\",\"expected_version\":$shipment_version,\"reason\":\"Smoke: khach tu choi\"}")"
shipment_version="$(ord04_event back "{\"event_type\":\"RETURNED\",\"expected_version\":$shipment_version,\"evidence_ref\":\"smoke-back\"}")"
result="$(ord04_wait "$ops" "$order_b" "d['status'] + ' ' + str(s.get('step')) + ' ' + str(s.get('status'))" 'RETURNING WAIT_RETURN WAITING')"
test "$result" = 'RETURNING WAIT_RETURN WAITING' || ord04_fail return "order B: $result"
test "$(ord04_available)" = "$((stock_b - 2))" || ord04_fail return 'stock was restored before the goods were counted'
item_id="$(ord04_admin "$ops" "$order_b" "d['items'][0]['order_item_id']")"
version="$(ord04_admin "$ops" "$order_b" "d['version']")"
return_body="{\"expected_version\":$version,\"reason\":\"Smoke: khach tu choi nhan\",\"evidence_ref\":\"smoke-slip\",\"items\":[{\"order_item_id\":\"$item_id\",\"received_quantity\":2,\"restock_quantity\":1,\"condition\":\"1 cai ban\"}]}"
ord04_post "$ops" "/admin/api/v1/orders/$order_b/returns" "smoke-$catalog_suffix-ord04-b-return" "$return_body" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]=="OK" and d["data"]["return"]["status"]=="REQUESTED", d'
# A replay of the same key answers the stored 202 and adds no second slip.
ord04_post "$ops" "/admin/api/v1/orders/$order_b/returns" "smoke-$catalog_suffix-ord04-b-return" "$return_body" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]=="OK", d'
result="$(ord04_wait "$ops" "$order_b" "d['status'] + ' ' + str(len(d['returns'])) + ' ' + d['returns'][0]['status'] + ' ' + str(s.get('status'))" 'RETURNED * COMPLETED DONE')"
test "$result" = 'RETURNED 1 COMPLETED DONE' || ord04_fail return "order B: $result"
test "$(ord04_available)" = "$((stock_b - 1))" || ord04_fail return "stock $(ord04_available) != $((stock_b - 1))"
rm -f "$jar_b"
echo 'ORD-04 return PASS'

# (C) A guest cancels its own order while it waits for OPS; another caller cannot.
stock_c="$(ord04_available)"
jar_c="$(mktemp)"
order_c="$(ord04_new_order "$jar_c" c 1)"
ops="$(ord04_token)"
[[ "$(ord04_wait "$ops" "$order_c" "d['status'] + ' ' + str(s.get('step'))" 'CONFIRMED WAIT_OPS')" == 'CONFIRMED WAIT_OPS' ]] || ord04_fail 'customer cancel' 'order C did not reach WAIT_OPS'
customer_order="$(curl -sS -b "$jar_c" "$GATEWAY_URL/api/v1/orders/$order_c")"
version="$(python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["allowed_actions"]==["CANCEL"], d; print(d["version"])' <<<"$customer_order")"
cancel_body="{\"expected_version\":$version,\"reason\":\"Smoke: doi y\"}"
test "$(status -X POST -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-ord04-c-cancel" \
  -d "$cancel_body" "$GATEWAY_URL/api/v1/orders/$order_c/cancel")" = 404
curl -sS -b "$jar_c" -X POST -H "Origin: $STOREFRONT_URL" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$catalog_suffix-ord04-c-cancel" \
  -d "$cancel_body" "$GATEWAY_URL/api/v1/orders/$order_c/cancel" | \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["code"]=="OK" and d["data"]["status"]=="CANCELLING", d'
for _ in $(seq 1 40); do
  result="$(curl -sS -b "$jar_c" "$GATEWAY_URL/api/v1/orders/$order_c" | \
    python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d["status"] + " " + d["processing_status"] + " " + str(d["reason_code"]))')"
  [[ "$result" == CANCELLED* ]] && break
  sleep 1
done
test "$result" = 'CANCELLED FINISHED CUSTOMER_CANCELLED' || ord04_fail 'customer cancel' "order C: $result"
test "$(ord04_available)" = "$stock_c" || ord04_fail 'customer cancel' "stock $(ord04_available) != $stock_c"
rm -f "$jar_c"
echo 'ORD-04 customer cancel PASS'
# TASK:CAT-02b: public detail is cacheable for 5 s (Redis keeps it 300 s until invalidated).
curl -sS -o /dev/null -D "$headers" "$GATEWAY_URL/api/v1/catalog/products/$product_id"
grep -qi '^cache-control: public, max-age=5' "$headers"
test "$(curl -sS -o /dev/null -D "$headers" -w '%{http_code}' "$image_url")" = 200
etag="$(grep -i '^etag:' "$headers" | cut -d' ' -f2- | tr -d '\r')"
test -n "$etag"
grep -qi '^content-type: image/png' "$headers"
test "$(status -H "If-None-Match: $etag" "$image_url")" = 304
catalog_request 200 POST "/products/$product_id/unpublish" '{"expected_version":3}' "smoke-$catalog_suffix-unpublish" |   python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["status"] != "ACTIVE", d'
test "$(status "$image_url")" = 404
test "$(status -H "If-None-Match: $etag" "$image_url")" = 404
# TASK:CAT-02b: the cached detail disappears once the durable invalidation task runs (worker every 1 s).
for _ in $(seq 1 20); do
  [[ "$(status "$GATEWAY_URL/api/v1/catalog/products/$product_id")" = 404 ]] && break
  sleep 0.5
done
test "$(status "$GATEWAY_URL/api/v1/catalog/products/$product_id")" = 404
echo 'CAT-02b cache invalidation PASS'
echo 'CAT-03 publish/public read/304/unpublish PASS'
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
