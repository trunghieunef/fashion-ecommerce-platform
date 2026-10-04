#!/usr/bin/env bash
# TASK:PLT-04.E: render the staging overlay (what Argo CD applies). Offline; creates nothing.
# --strict: fail while image registry/digests are still placeholders (run on the digest PR).
set -euo pipefail
cd "$(dirname "$0")/.."

rendered="$(kubectl kustomize infra/environments/staging)"
if [[ "${1:-}" == "--strict" ]] && grep -Eq 'ecr-registry-pending|@sha256:0{64}' <<<"$rendered"; then
  echo 'Staging overlay still has placeholder registry/digests; set them from the publish run.' >&2
  exit 1
fi
printf '%s\n' "$rendered"
