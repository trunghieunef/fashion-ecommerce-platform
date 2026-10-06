#!/usr/bin/env bash
# TASK:USR-01b-ii: connected Redis stalls must return the public 503 contract, not Gateway 504.
# Run after local-up.sh. Pauses only local Redis, restores it on every exit; no data deletion.
set -euo pipefail
cd "$(dirname "$0")/.."
C=(docker compose --env-file "${ENV_FILE:-infra/local/.env}" -f infra/local/compose.yaml)
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
body="$(mktemp)"
paused=false
restore() {
  if "$paused"; then "${C[@]}" unpause redis >/dev/null; fi
  rm -f "$body"
}
trap restore EXIT

email="redis-timeout-$(date +%s)-$RANDOM@example.test"
registration="{\"email\":\"$email\",\"password\":\"Smoke-pass-123\",\"full_name\":\"Timeout Smoke\",\"locale\":\"vi\"}" # gitleaks:allow synthetic test password
test "$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d "$registration" "$GATEWAY_URL/api/v1/auth/register")" = 201
# Warm the actual service Redis connection before pausing it.
status="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{}' "$GATEWAY_URL/api/v1/auth/login")"
test "$status" = 401 || test "$status" = 429
counts() {
  "${C[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d users -Atc \
    "select (select count(*) from refresh_tokens), (select count(*) from user_action_tokens), (select count(*) from outbox_events)"'
}
before="$(counts)"
"${C[@]}" pause redis >/dev/null
paused=true
for path in login password/forgot; do
  status="$(curl --max-time 5 -sS -o "$body" -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"Smoke-pass-123\"}" "$GATEWAY_URL/api/v1/auth/$path")" # gitleaks:allow synthetic test password
  if [[ "$status" != 503 ]]; then
    echo "FAIL: $path returned $status; expected public 503" >&2
    exit 1
  fi
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["code"] == "TEMPORARILY_UNAVAILABLE", d; assert d["metadata"]["request_id"] and d["metadata"]["trace_id"], d' "$body"
done
health() {
  # Git Bash must not rewrite the container's /dev/null into a Windows path.
  MSYS_NO_PATHCONV=1 "${C[@]}" exec -T user-service curl --max-time 5 -sS -o /dev/null \
    -w '%{http_code}' "http://localhost:8082/actuator/health/$1"
}
test "$(health readiness)" = 503
test "$(health liveness)" = 200
test "$(counts)" = "$before"
"${C[@]}" unpause redis >/dev/null
paused=false
for _ in $(seq 20); do
  status="$(health readiness)"
  if [[ "$status" = 200 ]]; then echo 'Redis timeout through Gateway smoke PASS'; exit 0; fi
  sleep 1
done
echo 'FAIL: readiness did not recover' >&2
exit 1
