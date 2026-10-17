#!/usr/bin/env bash
# Start one independently deployed provider application from its own module directory.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$(cd "$DEPLOY_DIR/../jsnx-edsp-platform-backend" && pwd)"
MODULE_NAME="${1:-}"
PORT="${2:-}"
if [[ ! "$MODULE_NAME" =~ ^jsnx-edsp-data-[a-z0-9-]+$ || ! "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
  echo "用法：$0 <数据应用模块名> <监听端口>" >&2
  exit 2
fi
MODULE_DIR="$BACKEND_DIR/data-applications/$MODULE_NAME"
JAR="$MODULE_DIR/target/$MODULE_NAME-2.0.0-SNAPSHOT.jar"
[[ -f "$JAR" ]] || { echo "未找到应用 JAR：$JAR" >&2; exit 1; }
for command_name in curl jq lsof python3; do
  command -v "$command_name" >/dev/null || { echo "缺少命令：$command_name" >&2; exit 1; }
done
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
[[ -x "$JAVA_HOME/bin/java" ]] || { echo "未找到 JDK 17" >&2; exit 1; }

NACOS_LOCAL_ENV="$DEPLOY_DIR/var/nacos/local.env"
[[ -f "$NACOS_LOCAL_ENV" ]] || { echo "未找到本地 Nacos 配置：$NACOS_LOCAL_ENV" >&2; exit 1; }
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

ready() {
  curl -fsS --max-time 2 "http://127.0.0.1:$PORT/internal/adapter/info" 2>/dev/null |
    jq -e --arg name "$MODULE_NAME" '.application == $name and .status == "UP"' >/dev/null 2>&1
}
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  if ready; then
    echo "$MODULE_NAME 已就绪：http://127.0.0.1:$PORT"
    exit 0
  fi
  echo "$PORT 已被占用，但 $MODULE_NAME 的接入接口未就绪；请检查该进程的启动目录和日志。" >&2
  exit 1
fi

mkdir -p "$DEPLOY_DIR/var/run" "$DEPLOY_DIR/var/logs"
LOG_FILE="$DEPLOY_DIR/var/logs/$MODULE_NAME.log"
PID_FILE="$DEPLOY_DIR/var/run/$MODULE_NAME.pid"
# Spring Boot searches ./config/*/application.properties. The deployment directory
# contains Nacos server settings, so start from the data application's directory.
cd "$MODULE_DIR"
export JAVA_HOME
python3 - "$JAR" "$LOG_FILE" "$PORT" <<'PY' > "$PID_FILE"
import os
import subprocess
import sys

with open(sys.argv[2], "ab") as log:
    process = subprocess.Popen(
        [os.path.join(os.environ["JAVA_HOME"], "bin", "java"), "-jar", sys.argv[1],
         "--server.port=" + sys.argv[3]],
        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    print(process.pid)
PY
for _ in {1..45}; do
  if ready; then
    echo "$MODULE_NAME 已就绪：http://127.0.0.1:$PORT"
    echo "日志：$LOG_FILE"
    exit 0
  fi
  if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then break; fi
  sleep 1
done
echo "$MODULE_NAME 未就绪，请查看 $LOG_FILE" >&2
exit 1
