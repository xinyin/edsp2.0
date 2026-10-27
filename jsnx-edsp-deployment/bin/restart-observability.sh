#!/usr/bin/env bash
# Rebuild and restart only the local observability service.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$(cd "$DEPLOY_DIR/../jsnx-edsp-platform-backend" && pwd)"
MODULE_DIR="$BACKEND_DIR/platform-services/jsnx-edsp-data-observability"
JAR="$MODULE_DIR/target/jsnx-edsp-data-observability-2.0.0-SNAPSHOT.jar"
PID_FILE="$DEPLOY_DIR/var/run/observability.pid"
LOG_FILE="$DEPLOY_DIR/var/logs/observability.log"
PORT=8083

for command_name in mvn curl lsof python3; do
  command -v "$command_name" >/dev/null || { echo "缺少命令：$command_name" >&2; exit 1; }
done
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
export JAVA_HOME
NACOS_LOCAL_ENV="$DEPLOY_DIR/var/nacos/local.env"
[[ -f "$NACOS_LOCAL_ENV" ]] || { echo "未找到 $NACOS_LOCAL_ENV" >&2; exit 1; }
set -a
source "$NACOS_LOCAL_ENV"
set +a
export NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
export NACOS_USERNAME="${NACOS_USERNAME:-${NACOS_ADMIN_USERNAME:-}}"
export NACOS_PASSWORD="${NACOS_PASSWORD:-${NACOS_ADMIN_PASSWORD:-}}"
export NACOS_CONFIG_USERNAME="${NACOS_CONFIG_USERNAME:-$NACOS_USERNAME}"
export NACOS_CONFIG_PASSWORD="${NACOS_CONFIG_PASSWORD:-$NACOS_PASSWORD}"
export NACOS_DISCOVERY_USERNAME="${NACOS_DISCOVERY_USERNAME:-$NACOS_USERNAME}"
export NACOS_DISCOVERY_PASSWORD="${NACOS_DISCOVERY_PASSWORD:-$NACOS_PASSWORD}"

listener_pid() { lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | sort -u || true; }
old_pid="$(listener_pid)"
if [[ -n "$old_pid" ]]; then
  [[ -f "$PID_FILE" && "$(<"$PID_FILE")" == "$old_pid" ]] || {
    echo "8083 的进程不属于当前平台的 PID 记录，拒绝重启。" >&2; exit 1;
  }
  running_dir="$(lsof -a -p "$old_pid" -d cwd -Fn | sed -n 's/^n//p')"
  [[ "$running_dir" == "$MODULE_DIR" ]] || {
    echo "观测服务的进程目录不匹配，拒绝重启。" >&2; exit 1;
  }
fi

mvn -q -f "$BACKEND_DIR/pom.xml" -pl platform-services/jsnx-edsp-data-observability -am package -DskipTests
[[ -f "$JAR" ]] || { echo "未找到观测服务 JAR" >&2; exit 1; }

if [[ -n "$old_pid" ]]; then
  [[ "$(listener_pid)" == "$old_pid" ]] || { echo "8083 进程已变化，请重试。" >&2; exit 1; }
  kill -TERM "$old_pid"
  for _ in {1..30}; do
    [[ -z "$(listener_pid)" ]] && break
    sleep 1
  done
fi
[[ -z "$(listener_pid)" ]] || { echo "旧观测服务尚未退出。" >&2; exit 1; }

mkdir -p "$DEPLOY_DIR/var/run" "$DEPLOY_DIR/var/logs"
python3 - "$MODULE_DIR" "$JAR" "$LOG_FILE" "$PORT" <<'PY' > "$PID_FILE"
import os
import subprocess
import sys

environment = os.environ.copy()
environment["SERVER_PORT"] = sys.argv[4]
with open(sys.argv[3], "ab") as log:
    process = subprocess.Popen(
        [os.path.join(environment["JAVA_HOME"], "bin", "java"), "-jar", sys.argv[2]],
        cwd=sys.argv[1], env=environment, stdin=subprocess.DEVNULL,
        stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
    )
    print(process.pid)
PY
new_pid="$(<"$PID_FILE")"
for _ in {1..40}; do
  kill -0 "$new_pid" 2>/dev/null || { echo "观测服务启动失败，请查看 $LOG_FILE" >&2; exit 1; }
  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
    echo "观测服务已重载：http://127.0.0.1:$PORT"
    exit 0
  fi
  sleep 1
done
echo "观测服务启动超时，请查看 $LOG_FILE" >&2
exit 1
