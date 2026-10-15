#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "启动外部数据平台"
if ! "$DEPLOY_DIR/bin/start-platform.sh"; then
  echo "外部数据平台启动失败，请检查平台服务日志。" >&2
  exit 1
fi

cat <<'EOF'

外部数据平台已启动：
  平台前端：http://127.0.0.1:5173/
  Gateway：http://127.0.0.1:8080/

平台登录：
  用户名：admin
  密码：admin123

如需同时启动可选复杂能力服务：
  START_CAPABILITY_SERVICE=1 ./bin/start-demo.sh
EOF
