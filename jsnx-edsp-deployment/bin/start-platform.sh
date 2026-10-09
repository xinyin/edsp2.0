#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKSPACE_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
BACKEND_DIR="$WORKSPACE_DIR/jsnx-edsp-platform-backend"
SERVICES_DIR="$BACKEND_DIR/platform-services"
APPLICATIONS_DIR="$BACKEND_DIR/data-applications"
PLATFORM_WEB="$WORKSPACE_DIR/jsnx-edsp-platform-frontend"
RUN_DIR="$DEPLOY_DIR/var/run"
LOG_DIR="$DEPLOY_DIR/var/logs"
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
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
DB_URL="${DB_URL:-jdbc:mysql://$DB_HOST:$DB_PORT/edsp_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}"
mkdir -p "$RUN_DIR" "$LOG_DIR"

for required_command in mysql mvn lsof curl; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "未找到命令：$required_command，请先安装并加入 PATH。" >&2
    exit 1
  }
done
command -v npm >/dev/null 2>&1 || {
  echo "未找到命令：npm，请先安装 Node.js。" >&2
  exit 1
}

port_is_open() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
pid_owns_port() {
  local pid="$1" port="$2"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null &&
    lsof -nP -a -p "$pid" -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

# Check the fixed Gateway port before initializing, building, or starting any
# other platform service. A stale single-application process must not leave a
# partially started platform behind.
if port_is_open 8080; then
  gateway_pid="$(cat "$RUN_DIR/gateway.pid" 2>/dev/null || true)"
  if pid_owns_port "$gateway_pid" 8080; then
    echo "Gateway 已由本脚本记录运行，稍后会跳过启动。"
  else
    echo "8080 已被其他进程占用，请先停止旧单体后端后重试。" >&2
    exit 1
  fi
fi

wait_for_port() {
  local port="$1" name="$2"
  for _ in {1..40}; do
    port_is_open "$port" && { echo "$name 已启动：http://127.0.0.1:$port"; return; }
    sleep 1
  done
  echo "$name 启动失败，请检查 $LOG_DIR/$name.log" >&2
  return 1
}
start_service() {
  local name="$1" port="$2" module="$3" jar="$4"
  if port_is_open "$port"; then echo "$name 已在 $port 运行，跳过"; return; fi
  rm -f "$RUN_DIR/$name.pid"
  (
    cd "$SERVICES_DIR/$module"
    nohup env JAVA_HOME="$JAVA_HOME" SERVER_PORT="$port" DB_URL="$DB_URL" DB_USERNAME="$DB_USERNAME" DB_PASSWORD="$DB_PASSWORD" \
      ADMIN_URL="http://127.0.0.1:8081" OBSERVABILITY_URL="http://127.0.0.1:8083" \
      "$JAVA_HOME/bin/java" -jar "target/$jar" >"$LOG_DIR/$name.log" 2>&1 &
    echo $! >"$RUN_DIR/$name.pid"
  )
  wait_for_port "$port" "$name"
}
start_application() {
  local name="$1" port="$2" module="$3" jar="$4"
  if port_is_open "$port"; then echo "$name 已在 $port 运行，跳过"; return; fi
  rm -f "$RUN_DIR/$name.pid"
  (
    cd "$APPLICATIONS_DIR/$module"
    nohup env JAVA_HOME="$JAVA_HOME" SERVER_PORT="$port" \
      ADMIN_URL="http://127.0.0.1:8081" OBSERVABILITY_URL="http://127.0.0.1:8083" \
      "$JAVA_HOME/bin/java" -jar "target/$jar" >"$LOG_DIR/$name.log" 2>&1 &
    echo $! >"$RUN_DIR/$name.pid"
  )
  wait_for_port "$port" "$name"
}

frontend_is_healthy() {
  local body
  body="$(curl --silent --show-error --max-time 2 http://127.0.0.1:5173/ 2>/dev/null || true)"
  [[ "$body" == *'name="edsp-platform"'* && "$body" == *'jsnx-edsp-platform-frontend'* ]]
}

reclaim_port() {
  local port="$1"
  local pids
  pids="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  [[ -n "$pids" ]] || return 0
  echo "回收端口 $port 上不健康的残留进程：$pids"
  while IFS= read -r pid; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done <<< "$pids"
  for _ in {1..10}; do
    port_is_open "$port" || return 0
    sleep 1
  done
  pids="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "端口 $port 的残留进程未退出，请手工执行：kill $pids" >&2
    return 1
  fi
}

MYSQL_ARGS=(-h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME")
[[ -n "$DB_PASSWORD" ]] && MYSQL_ARGS+=("-p$DB_PASSWORD")
running_backend=0
for port in 8080 8081 8083 8084 8181 8182 8183 8191; do
  if port_is_open "$port"; then running_backend=1; break; fi
done
if [[ "$running_backend" == "1" ]]; then
  echo "检测到平台后端正在运行，跳过数据库初始化和 Maven 构建。"
  echo "这样可避免覆盖运行中 Spring Boot JAR，导致后续请求类加载失败。"
  echo "需要应用后端代码时请先停止平台，或执行 bin/reload-integration-stack.sh。"
else
  echo "初始化外部数据平台数据库..."
  PLATFORM_SCHEMA="$SERVICES_DIR/jsnx-edsp-data-admin/src/main/resources/db/schema.sql"
  PLATFORM_DATA="$SERVICES_DIR/jsnx-edsp-data-admin/src/main/resources/db/data.sql"
  if [[ "${SEED_DEMO_DATA:-0}" == "1" ]]; then
    echo "SEED_DEMO_DATA=1，载入平台演示样例数据。"
    PLATFORM_INIT_SQL="source $PLATFORM_SCHEMA; source $PLATFORM_DATA;"
  else
    echo "默认空数据模式：仅初始化表结构，不载入平台演示样例。"
    PLATFORM_INIT_SQL="source $PLATFORM_SCHEMA;"
  fi
  if ! mysql "${MYSQL_ARGS[@]}" -e "$PLATFORM_INIT_SQL"; then
    echo "无法连接 MySQL（$DB_HOST:$DB_PORT），请先启动 MySQL 后重试。" >&2
    exit 1
  fi
  export JAVA_HOME
  (cd "$BACKEND_DIR" && mvn -q -DskipTests clean package)
fi

start_service admin 8081 jsnx-edsp-data-admin jsnx-edsp-data-admin-2.0.0-SNAPSHOT.jar
start_service observability 8083 jsnx-edsp-data-observability jsnx-edsp-data-observability-2.0.0-SNAPSHOT.jar
# Optional complex-capability service. The normal data path does not depend on it.
if [[ "${START_CAPABILITY_SERVICE:-0}" == "1" ]]; then
  start_service capability-service 8091 jsnx-edsp-capability-runtime jsnx-edsp-capability-runtime-2.0.0-SNAPSHOT.jar
fi
start_application data-enterprise 8181 jsnx-edsp-data-enterprise jsnx-edsp-data-enterprise-2.0.0-SNAPSHOT.jar
start_application data-antifraud 8183 jsnx-edsp-data-antifraud jsnx-edsp-data-antifraud-2.0.0-SNAPSHOT.jar
start_application data-credit 8182 jsnx-edsp-data-credit jsnx-edsp-data-credit-2.0.0-SNAPSHOT.jar
start_application data-lhzx 8191 jsnx-edsp-data-source-lhzx jsnx-edsp-data-source-lhzx-2.0.0-SNAPSHOT.jar
# Local deployment registration. Production must configure the LHZX F5/VIP in
# data_source.runtime_url instead of using this loopback address.
mysql "${MYSQL_ARGS[@]}" -D edsp_demo -e "UPDATE data_source SET application_name='jsnx-edsp-data-source-lhzx',runtime_url='http://127.0.0.1:8191',config_version=config_version+1 WHERE source_code='LHZX' AND (runtime_url IS NULL OR runtime_url='' OR application_name<>'jsnx-edsp-data-source-lhzx');"
start_service esb-adapter 8084 jsnx-edsp-esb-adapter jsnx-edsp-esb-adapter-2.0.0-SNAPSHOT.jar
start_service gateway 8080 jsnx-edsp-data-gateway jsnx-edsp-data-gateway-2.0.0-SNAPSHOT.jar

if port_is_open 5173; then
  if frontend_is_healthy; then
    echo "平台前端已在 5173 运行，跳过"
  else
    echo "5173 已被不健康或非本 Demo 前端进程占用，准备回收。"
    reclaim_port 5173
  fi
fi
if ! port_is_open 5173; then
  [[ -d "$PLATFORM_WEB/node_modules" ]] || (cd "$PLATFORM_WEB" && npm install)
  (cd "$PLATFORM_WEB" && nohup ./node_modules/.bin/vite --host 127.0.0.1 >"$LOG_DIR/frontend.log" 2>&1 & echo $! >"$RUN_DIR/frontend.pid")
  wait_for_port 5173 frontend
  frontend_is_healthy || {
    echo "平台前端端口已监听，但首页未返回当前 Demo 页面，请检查 $LOG_DIR/frontend.log" >&2
    exit 1
  }
fi
echo "平台启动完成：http://127.0.0.1:5173/"
