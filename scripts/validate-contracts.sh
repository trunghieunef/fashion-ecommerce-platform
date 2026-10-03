#!/usr/bin/env bash
# Lint OpenAPI contracts and validate their examples against the schemas.
set -euo pipefail
cd "$(dirname "$0")/.."
# No telemetry and no npm update check: validation must not call external services.
export REDOCLY_TELEMETRY=off REDOCLY_SUPPRESS_UPDATE_NOTICE=true
npx --no-install redocly lint contracts/openapi/*.yaml
python3 -c 'import jsonschema, rfc3339_validator' 2>/dev/null || {
  echo 'Missing contract tooling: python3 -m pip install -r tests/contracts/requirements.txt (in a virtualenv)' >&2
  exit 1
}
python3 -B -m unittest discover -s tests/contracts -p 'test_*.py' -v
