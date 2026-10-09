#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/2] 启动上游供应商模拟服务"
"$DEPLOY_DIR/bin/start-vendor.sh"

echo "[2/2] 启动外部数据平台"
if ! "$DEPLOY_DIR/bin/start-platform.sh"; then
  echo "外部数据平台启动失败，清理本次已启动的平台和供应商进程。" >&2
  "$DEPLOY_DIR/bin/stop-platform.sh" || true
  "$DEPLOY_DIR/bin/stop-vendor.sh" || true
  exit 1
fi

cat <<'EOF'

EDSP 2.0 Demo 已启动：
  平台前端：http://127.0.0.1:5173/
  供应商前端：http://127.0.0.1:5190/
  Gateway：http://127.0.0.1:8080/

平台登录：
  用户名：admin
  密码：admin123

下一步可执行：
  ./bin/run-full-demo.sh
  ./bin/verify-demo.sh

如需同时启动可选复杂能力服务：
  START_CAPABILITY_SERVICE=1 ./bin/start-demo.sh
EOF
