#!/usr/bin/env bash
# TASK:PLT-04: offline checks of prepared infrastructure. Creates nothing and calls no AWS API;
# `aws cloudformation validate-template`/change sets run only after PLT-04.A approval (16).
set -euo pipefail
cd "$(dirname "$0")/.."
{ command -v cfn-lint >/dev/null && python3 -c 'import yaml' 2>/dev/null; } || {
  echo 'Missing infra tooling: python3 -m pip install -r tests/infrastructure/requirements.txt (in a virtualenv)' >&2
  exit 1
}
cfn-lint infra/aws/cloudformation/*.yaml
python3 -B -m unittest discover -s tests/infrastructure -p 'test_*.py' -v
