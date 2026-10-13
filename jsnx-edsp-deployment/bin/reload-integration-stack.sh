#!/usr/bin/env bash
# Reload the complete integration path without reinitializing MySQL.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$(cd "$DEPLOY_DIR/../jsnx-edsp-platform-backend" && pwd)"
RUN_DIR="$DEPLOY_DIR/var/run"
LOG_DIR="$DEPLOY_DIR/var/logs"
JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export JAVA_HOME
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
DB_URL="${DB_URL:-jdbc:mysql://$DB_HOST:$DB_PORT/edsp_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}"
NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_LOCAL_ENV="$DEPLOY_DIR/var/nacos/local.env"
[[ -f "$NACOS_LOCAL_ENV" ]] || { echo "未找到 $NACOS_LOCAL_ENV，请先安装 Nacos。" >&2; exit 1; }
set -a
source "$NACOS_LOCAL_ENV"
set +a
NACOS_USERNAME="${NACOS_USERNAME:-${NACOS_ADMIN_USERNAME:-}}"
NACOS_PASSWORD="${NACOS_PASSWORD:-${NACOS_ADMIN_PASSWORD:-}}"
NACOS_CONFIG_USERNAME="${NACOS_CONFIG_USERNAME:-$NACOS_USERNAME}"
NACOS_CONFIG_PASSWORD="${NACOS_CONFIG_PASSWORD:-$NACOS_PASSWORD}"
NACOS_DISCOVERY_USERNAME="${NACOS_DISCOVERY_USERNAME:-$NACOS_USERNAME}"
NACOS_DISCOVERY_PASSWORD="${NACOS_DISCOVERY_PASSWORD:-$NACOS_PASSWORD}"
export NACOS_SERVER_ADDR NACOS_USERNAME NACOS_PASSWORD
export EDSP_CODE_GENERATION_ROOT="${EDSP_CODE_GENERATION_ROOT:-$DEPLOY_DIR/var/generated-applications}"
export NACOS_CONFIG_USERNAME NACOS_CONFIG_PASSWORD
export NACOS_DISCOVERY_USERNAME NACOS_DISCOVERY_PASSWORD
mkdir -p "$RUN_DIR" "$LOG_DIR"

for required_command in mysql mvn lsof curl; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "未找到命令：$required_command，请先安装并加入 PATH。" >&2
    exit 1
  }
done
CHECK_ONLY=1 "$DEPLOY_DIR/bin/sync-config-to-nacos.sh"

listener_pid() { lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | sort -u || true; }
pid_record_owns_listener() {
  local recorded_pid="$1"
  local listener="$2"
  [[ "$recorded_pid" == "$listener" ]] && return 0
  # Older versions of this reload script recorded the launcher shell PID.
  # Accept its direct Java child once, then the corrected start_one below
  # writes the listener PID itself on subsequent reloads.
  pgrep -P "$recorded_pid" 2>/dev/null | grep -Fxq "$listener"
}
verify_owner() {
  local name="$1"
  local port="$2"
  local pid_file="$RUN_DIR/$name.pid"
  local current
  local recorded
  current="$(listener_pid "$port")"
  [[ -z "$current" ]] && return 0
  recorded="$(cat "$pid_file" 2>/dev/null || true)"
  [[ -n "$recorded" ]] && pid_record_owns_listener "$recorded" "$current" || {
    echo "$name 的 $port 端口并非由 Demo PID 文件管理，停止重载。" >&2; exit 1;
  }
}
stop_one() {
  local name="$1" port="$2" pid
  pid="$(listener_pid "$port")"
  [[ -z "$pid" ]] && return 0
  kill -TERM "$pid"
  for _ in {1..30}; do [[ -z "$(listener_pid "$port")" ]] && return 0; sleep 1; done
  echo "$name 未在 30 秒内退出。" >&2; exit 1
}
wait_health() {
  local name="$1" port="$2"
  for _ in {1..50}; do
    curl -fsS --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && { echo "$name 已就绪：http://127.0.0.1:$port"; return; }
    sleep 1
  done
  echo "$name 启动失败，请查看 $LOG_DIR/$name.log" >&2; exit 1
}
start_one() {
  local name="$1" port="$2" dir="$3" jar="$4"
  (
    cd "$dir"
    nohup env JAVA_HOME="$JAVA_HOME" NACOS_SERVER_ADDR="$NACOS_SERVER_ADDR" \
      NACOS_USERNAME="$NACOS_USERNAME" NACOS_PASSWORD="$NACOS_PASSWORD" \
      NACOS_CONFIG_USERNAME="$NACOS_CONFIG_USERNAME" NACOS_CONFIG_PASSWORD="$NACOS_CONFIG_PASSWORD" \
      NACOS_DISCOVERY_USERNAME="$NACOS_DISCOVERY_USERNAME" NACOS_DISCOVERY_PASSWORD="$NACOS_DISCOVERY_PASSWORD" \
      "$JAVA_HOME/bin/java" -jar "target/$jar" \
      >"$LOG_DIR/$name.log" 2>&1 < /dev/null &
    echo $! >"$RUN_DIR/$name.pid"
  )
  wait_health "$name" "$port"
}

verify_owner admin 8081
verify_owner observability 8083
verify_owner data-enterprise 8181
verify_owner data-credit 8182
verify_owner data-antifraud 8183
verify_owner data-lhzx 8191
verify_owner gateway 8080
verify_owner esb-adapter 8084
echo "停止旧的联调运行栈..."
stop_one esb-adapter 8084
stop_one gateway 8080
stop_one observability 8083
stop_one data-antifraud 8183
stop_one data-lhzx 8191
stop_one data-credit 8182
stop_one data-enterprise 8181
stop_one admin 8081

echo "构建 ESB Adapter、Gateway、Admin、Observability、公共 SDK 和数据应用..."
(cd "$BACKEND_DIR" && mvn -q -pl platform-services/jsnx-edsp-esb-adapter,platform-services/jsnx-edsp-data-gateway,platform-services/jsnx-edsp-data-admin,platform-services/jsnx-edsp-data-observability,data-applications/jsnx-edsp-data-enterprise,data-applications/jsnx-edsp-data-credit,data-applications/jsnx-edsp-data-antifraud,data-applications/jsnx-edsp-data-source-lhzx -am package)

start_one admin 8081 "$BACKEND_DIR/platform-services/jsnx-edsp-data-admin" jsnx-edsp-data-admin-2.0.0-SNAPSHOT.jar
start_one observability 8083 "$BACKEND_DIR/platform-services/jsnx-edsp-data-observability" jsnx-edsp-data-observability-2.0.0-SNAPSHOT.jar
start_one data-enterprise 8181 "$BACKEND_DIR/data-applications/jsnx-edsp-data-enterprise" jsnx-edsp-data-enterprise-2.0.0-SNAPSHOT.jar
start_one data-credit 8182 "$BACKEND_DIR/data-applications/jsnx-edsp-data-credit" jsnx-edsp-data-credit-2.0.0-SNAPSHOT.jar
start_one data-antifraud 8183 "$BACKEND_DIR/data-applications/jsnx-edsp-data-antifraud" jsnx-edsp-data-antifraud-2.0.0-SNAPSHOT.jar
start_one data-lhzx 8191 "$BACKEND_DIR/data-applications/jsnx-edsp-data-source-lhzx" jsnx-edsp-data-source-lhzx-2.0.0-SNAPSHOT.jar
mysql_args=(-h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME")
[[ -n "$DB_PASSWORD" ]] && mysql_args+=("-p$DB_PASSWORD")
mysql "${mysql_args[@]}" -D edsp_demo -e "UPDATE data_source SET application_name='jsnx-edsp-data-source-lhzx',runtime_url='http://127.0.0.1:8191',config_version=config_version+1 WHERE source_code='LHZX' AND (runtime_url IS NULL OR runtime_url='' OR application_name<>'jsnx-edsp-data-source-lhzx');"
start_one gateway 8080 "$BACKEND_DIR/platform-services/jsnx-edsp-data-gateway" jsnx-edsp-data-gateway-2.0.0-SNAPSHOT.jar
start_one esb-adapter 8084 "$BACKEND_DIR/platform-services/jsnx-edsp-esb-adapter" jsnx-edsp-esb-adapter-2.0.0-SNAPSHOT.jar

for port in 8181 8182 8183 8191; do
  allowed="$(curl -fsS -X OPTIONS -D - -o /dev/null --max-time 3 "http://127.0.0.1:$port/internal/adapter/execute")"
  printf '%s' "$allowed" | tr -d '\r' | grep -Eiq '^allow:.*POST' || { echo "$port 未暴露草稿联调入口。" >&2; exit 1; }
done
curl -fsS --max-time 3 "http://127.0.0.1:8080/api/security-algorithms" | grep -q 'AES_256_GCM' || {
  echo "Gateway 未正确转发安全算法目录。" >&2; exit 1;
}
route_status="$(curl -fsS --max-time 3 "http://127.0.0.1:8080/internal/routes/status")"
printf '%s' "$route_status" | grep -q '"healthy":true' || {
  echo "Gateway 动态路由快照未就绪：$route_status" >&2; exit 1;
}
curl -fsS --max-time 3 "http://127.0.0.1:8084/esb/queue/status" | grep -q 'IN_MEMORY_REQUEST_REPLY' || {
  echo "ESB Adapter 模拟队列未就绪。" >&2; exit 1;
}
echo "联调运行栈重载完成；MySQL 数据未重新初始化。"
