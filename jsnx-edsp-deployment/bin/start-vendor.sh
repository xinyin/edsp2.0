#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKSPACE_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
RUN_DIR="$DEPLOY_DIR/var/run"
LOG_DIR="$DEPLOY_DIR/var/logs"
VENDOR_SERVICE="$WORKSPACE_DIR/jsnx-edsp-vendor-backend"
VENDOR_WEB="$WORKSPACE_DIR/jsnx-edsp-vendor-frontend"
VENDOR_PORT="${VENDOR_SERVER_PORT:-8090}"
JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
fi
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "未找到 Java 17。请设置 JAVA_HOME，或先安装 JDK 17。" >&2
  exit 1
fi
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_URL="${DB_URL:-jdbc:mysql://$DB_HOST:$DB_PORT/vendor_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"

mkdir -p "$RUN_DIR" "$LOG_DIR"

for required_command in mysql mvn lsof; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "未找到命令：${required_command}，请先安装并加入 PATH。" >&2
    exit 1
  }
done
command -v npm >/dev/null 2>&1 || {
  echo "未找到命令：npm，请先安装 Node.js。" >&2
  exit 1
}

port_is_open() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

wait_for_port() {
  local port="$1"
  local name="$2"
  for _ in {1..30}; do
    if port_is_open "$port"; then
      echo "$name 已启动：http://127.0.0.1:$port"
      return 0
    fi
    if [[ -f "$RUN_DIR/$name.pid" ]] && ! kill -0 "$(cat "$RUN_DIR/$name.pid")" 2>/dev/null; then
      echo "$name 进程已退出，请检查 $LOG_DIR/$name.log" >&2
      return 1
    fi
    sleep 1
  done
  echo "$name 启动超时，请检查 $LOG_DIR/$name.log" >&2
  return 1
}

MYSQL_ARGS=(-h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME")
if [[ -n "$DB_PASSWORD" ]]; then
  MYSQL_ARGS+=("-p$DB_PASSWORD")
fi

echo "初始化上游供应商模拟数据库..."
VENDOR_SCHEMA="$VENDOR_SERVICE/src/main/resources/db/schema.sql"
VENDOR_DATA="$VENDOR_SERVICE/src/main/resources/db/data.sql"
if [[ "${SEED_DEMO_DATA:-0}" == "1" ]]; then
  echo "SEED_DEMO_DATA=1，载入上游供应商演示样例数据。"
  mysql "${MYSQL_ARGS[@]}" -e "source $VENDOR_SCHEMA; source $VENDOR_DATA;"
else
  echo "默认空数据模式：仅初始化表结构，不载入供应商演示样例。"
  mysql "${MYSQL_ARGS[@]}" -e "source $VENDOR_SCHEMA;"
fi

if port_is_open "$VENDOR_PORT"; then
  echo "供应商后端已在 $VENDOR_PORT 端口运行，跳过启动。"
  echo "提示：该进程不是本次启动，stop-vendor.sh 默认不会停止它。"
else
  rm -f "$RUN_DIR/vendor.pid"
  echo "构建上游供应商后端..."
  (cd "$VENDOR_SERVICE" && env JAVA_HOME="$JAVA_HOME" mvn -q -DskipTests clean package)
  echo "启动上游供应商后端..."
  (
    cd "$VENDOR_SERVICE"
    nohup env -u SERVER_PORT JAVA_HOME="$JAVA_HOME" VENDOR_SERVER_PORT="$VENDOR_PORT" \
      DB_URL="$DB_URL" DB_USERNAME="$DB_USERNAME" DB_PASSWORD="$DB_PASSWORD" \
      "$JAVA_HOME/bin/java" -jar target/jsnx-edsp-vendor-simulator-1.0.0-SNAPSHOT.jar >"$LOG_DIR/vendor.log" 2>&1 &
    echo $! >"$RUN_DIR/vendor.pid"
  )
  wait_for_port "$VENDOR_PORT" vendor
fi

if port_is_open 5190; then
  echo "供应商前端已在 5190 端口运行，跳过启动。"
  echo "提示：该进程不是本次启动，stop-vendor.sh 默认不会停止它。"
else
  rm -f "$RUN_DIR/vendor-frontend.pid"
  if [[ ! -d "$VENDOR_WEB/node_modules" ]]; then
    echo "安装供应商前端独立依赖..."
    (cd "$VENDOR_WEB" && npm install)
  fi
  echo "启动上游供应商前端..."
  (
    cd "$VENDOR_WEB"
    nohup ./node_modules/.bin/vite --host 127.0.0.1 >"$LOG_DIR/vendor-frontend.log" 2>&1 &
    echo $! >"$RUN_DIR/vendor-frontend.pid"
  )
  wait_for_port 5190 vendor-frontend
fi

echo "上游供应商 Demo 启动完成：http://127.0.0.1:5190/"
