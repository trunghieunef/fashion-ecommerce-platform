#!/usr/bin/env bash
# Build host artifacts with the pinned toolchains, then start the S1-local stack.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-infra/local/.env}"
[[ -f "$ENV_FILE" ]] || cp infra/local/.env.example "$ENV_FILE"

./mvnw -q -DskipTests package
npm ci
npm run build --workspace @fashion/storefront
docker compose --env-file "$ENV_FILE" -f infra/local/compose.yaml up --build --wait -d
