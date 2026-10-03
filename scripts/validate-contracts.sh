#!/usr/bin/env bash
# Lint OpenAPI contracts and validate their examples against the schemas.
set -euo pipefail
cd "$(dirname "$0")/.."
# No telemetry and no npm update check: validation must not call external services.
export REDOCLY_TELEMETRY=off REDOCLY_SUPPRESS_UPDATE_NOTICE=true
npx --no-install redocly lint contracts/openapi/*.yaml
