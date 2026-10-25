#!/usr/bin/env bash
# TASK:PAY-02: kiểm thử tay với VNPay sandbox thật. CHỈ chạy local, không bao giờ trong CI.
# Dùng: bash scripts/vnpay-sandbox.sh [amount]   (mặc định 10000 VND)
# Cần: stack payment đang chạy với PAYMENT_VNPAY_TMN_CODE/HASH_SECRET trong infra/local/.env,
# và payment-service tới được tại PAYMENT_URL (mặc định http://localhost:8086).
# Không in token, secret hay giá trị trong .env. Thoát 0 khi SUCCESS, 1 khi FAILED/hết giờ.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-infra/local/.env}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8086}"
amount="${1:-10000}"
[[ "$amount" =~ ^[0-9]+$ && "$amount" -gt 0 ]] || { echo 'amount phải là số nguyên VND > 0' >&2; exit 2; }
[[ -f "$ENV_FILE" ]] || { echo "thiếu $ENV_FILE (chạy scripts/local-up.sh)" >&2; exit 2; }
grep -q '^PAYMENT_VNPAY_TMN_CODE=.' "$ENV_FILE" && grep -q '^PAYMENT_VNPAY_HASH_SECRET=.' "$ENV_FILE" ||
  { echo "điền PAYMENT_VNPAY_TMN_CODE và PAYMENT_VNPAY_HASH_SECRET trong $ENV_FILE rồi khởi động lại payment" >&2; exit 2; }

# Service token order-service (ADR-19, sống 60 s nên ký lại cho mỗi request). Khóa chỉ nằm trong bộ nhớ Node.
service_token() {
node --env-file="$ENV_FILE" - <<'NODE'
const { createPrivateKey, randomUUID, sign } = require('node:crypto');
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const header = encode({alg: 'ES256', typ: 'JWT', kid: 'order-service'});
const payload = encode({iss: 'order-service', sub: 'order-service', aud: ['payment-service'],
  jti: randomUUID(), iat: now, exp: now + 60});
const key = createPrivateKey({key: Buffer.from(process.env.ORDER_SERVICE_PRIVATE_KEY, 'base64'), type: 'pkcs8', format: 'der'});
const input = `${header}.${payload}`;
process.stdout.write(`${input}.${sign('sha256', Buffer.from(input), {key, dsaEncoding: 'ieee-p1363'}).toString('base64url')}`);
NODE
}
# curl với token qua stdin config để không lộ trong danh sách tiến trình.
api() { printf 'header = "Authorization: Bearer %s"\n' "$(service_token)" | curl --silent --show-error --config - "$@"; }
json_field() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const v=JSON.parse(s).data?.[process.argv[1]];process.stdout.write(v==null?"":String(v))})' "$1"; }

order_id="$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')"
order_no="SBX$(date +%Y%m%d%H%M%S)"
expires_at="$(node -e 'process.stdout.write(new Date(Date.now()+30*60*1000).toISOString())')"
body="{\"order_id\":\"$order_id\",\"order_no\":\"$order_no\",\"user_id\":null,\"method\":\"VNPAY\",\"amount\":$amount,\"currency\":\"VND\",\"expires_at\":\"$expires_at\",\"client_ip\":\"127.0.0.1\",\"locale\":\"vi\"}"

created="$(api -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: sbx-$order_id" \
  -d "$body" "$PAYMENT_URL/internal/api/v1/payments")"
payment_url="$(printf '%s' "$created" | json_field payment_url)"
[[ -n "$payment_url" ]] || { echo 'tạo payment thất bại (xem log payment; có thể thiếu caller order-service hoặc VNPAY tắt)' >&2; exit 1; }
echo "order_no=$order_no amount=$amount"
echo "Mở link, trả bằng thẻ test của VNPay (tài liệu sandbox):"
echo "$payment_url"

last=''
for _ in $(seq 1 80); do  # 80 x 15 s = 20 phút
  sleep 15
  status="$(api "$PAYMENT_URL/internal/api/v1/payments/by-order/$order_id" | json_field status || true)"
  [[ "$status" == "$last" ]] || { echo "status=$status"; last="$status"; }
  case "$status" in
    SUCCESS) exit 0 ;;
    FAILED) exit 1 ;;
  esac
done
echo 'hết 20 phút chưa thấy SUCCESS' >&2
exit 1
