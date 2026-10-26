#!/usr/bin/env bash
# Start the 9494 provider application on its registered local listener port.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/start-data-application.sh" jsnx-edsp-data-provider-9494 18883
