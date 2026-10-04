#!/usr/bin/env bash
# TASK:SEC-01: scan committed git history for secrets with gitleaks pinned by digest (17).
# Usage: bash scripts/scan-secrets.sh [repo-path]   (default: this repository)
# Exit 0 = no findings; non-zero = findings or scan error. Findings print redacted (rule, file, line).
set -euo pipefail

IMAGE='zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
REPO="$(cd "${1:-$(dirname "$0")/..}" && (pwd -W 2>/dev/null || pwd))"

# MSYS_NO_PATHCONV keeps Git Bash on Windows from rewriting the container path.
# safe.directory: the mounted repo is owned by another uid inside the container.
MSYS_NO_PATHCONV=1 docker run --rm --network none \
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
  -v "$REPO:/repo:ro" "$IMAGE" \
  git /repo --redact --verbose --no-banner --exit-code 1
