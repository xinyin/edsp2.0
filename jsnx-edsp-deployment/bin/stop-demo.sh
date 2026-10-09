#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "停止外部数据平台"
STOP_ORPHANED=1 "$DEPLOY_DIR/bin/stop-platform.sh"

echo "停止上游供应商模拟服务"
STOP_ORPHANED=1 "$DEPLOY_DIR/bin/stop-vendor.sh"

echo "EDSP 2.0 Demo 已停止。"
