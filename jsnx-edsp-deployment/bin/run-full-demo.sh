#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DEPLOY_DIR/bin/run-enterprise-demo.sh" "$@"
