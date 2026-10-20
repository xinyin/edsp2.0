#!/usr/bin/env bash
# Start the CSSJ provider application with its registered local listener port.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/start-data-application.sh" jsnx-edsp-data-provider-unassigned 18111
