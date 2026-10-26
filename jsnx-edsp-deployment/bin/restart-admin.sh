#!/usr/bin/env bash
# Rebuild and reload only the local Admin service; do not re-seed MySQL.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$(cd "$DEPLOY_DIR/../jsnx-edsp-platform-backend" && pwd)"
MODULE_DIR="$BACKEND_DIR/platform-services/jsnx-edsp-data-admin"
JAR="$MODULE_DIR/target/jsnx-edsp-data-admin-2.0.0-SNAPSHOT.jar"
PID_FILE="$DEPLOY_DIR/var/run/admin.pid"
LOG_FILE="$DEPLOY_DIR/var/logs/admin.log"
PORT=8081

for command_name in mvn curl lsof python3; do
  command -v "$command_name" >/dev/null || { echo "缺少命令：$command_name" >&2; exit 1; }
done
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
export JAVA_HOME
NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_LOCAL_ENV="$DEPLOY_DIR/var/nacos/local.env"
[[ -f "$NACOS_LOCAL_ENV" ]] || { echo "未找到 ${NACOS_LOCAL_ENV}，请先安装 Nacos。" >&2; exit 1; }
"$DEPLOY_DIR/bin/ensure-database-credential-key.sh"
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
export NACOS_CONFIG_USERNAME NACOS_CONFIG_PASSWORD
export NACOS_DISCOVERY_USERNAME NACOS_DISCOVERY_PASSWORD
export EDSP_CODE_GENERATION_ROOT="${EDSP_CODE_GENERATION_ROOT:-$DEPLOY_DIR/var/generated-applications}"
CHECK_ONLY=1 "$DEPLOY_DIR/bin/sync-config-to-nacos.sh"

listener_pid() { lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | sort -u || true; }
old_pid="$(listener_pid)"
if [[ -n "$old_pid" ]]; then
  [[ -f "$PID_FILE" && "$(cat "$PID_FILE")" == "$old_pid" ]] || {
    echo "8081 的进程不属于当前 Demo 的 PID 记录，已停止操作。" >&2; exit 1;
  }
  running_dir="$(lsof -a -p "$old_pid" -d cwd -Fn | sed -n 's/^n//p')"
  [[ "$running_dir" == "$MODULE_DIR" ]] || {
    echo "Admin 进程目录不匹配，已停止操作。" >&2; exit 1;
  }
fi

echo "构建 Core 和 Admin（不解析其他数据应用）..."
mvn -q -f "$BACKEND_DIR/common-components/jsnx-edsp-platform-core/pom.xml" install -DskipTests
mvn -q -f "$MODULE_DIR/pom.xml" clean package -DskipTests
[[ -f "$JAR" ]] || { echo "未找到 Admin JAR" >&2; exit 1; }

if [[ -n "$old_pid" ]]; then
  [[ "$(listener_pid)" == "$old_pid" ]] || { echo "进程已变化，请重试。" >&2; exit 1; }
  echo "停止旧 Admin（PID ${old_pid}）..."
  kill -TERM "$old_pid"
  for _ in {1..30}; do
    [[ -z "$(listener_pid)" ]] && break
    sleep 1
  done
fi
[[ -z "$(listener_pid)" ]] || { echo "Admin 尚未退出，请检查进程。" >&2; exit 1; }

mkdir -p "$DEPLOY_DIR/var/run" "$DEPLOY_DIR/var/logs"
if [[ -f "$LOG_FILE" ]]; then cp "$LOG_FILE" "$LOG_FILE.before-restart"; fi
echo "启动新版 Admin..."
export NACOS_SERVER_ADDR NACOS_USERNAME NACOS_PASSWORD
export NACOS_CONFIG_USERNAME NACOS_CONFIG_PASSWORD
export NACOS_DISCOVERY_USERNAME NACOS_DISCOVERY_PASSWORD
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
new_pid="$(cat "$PID_FILE")"
for _ in {1..40}; do
  if ! kill -0 "$new_pid" 2>/dev/null; then
    echo "Admin 启动失败，请查看 $LOG_FILE" >&2; exit 1;
  fi
  if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/dashboard" >/dev/null 2>&1; then
    allowed="$(curl -fsS -X OPTIONS -D - -o /dev/null --max-time 3 "http://127.0.0.1:$PORT/api/interfaces/0")"
    if ! printf '%s' "$allowed" | tr -d '\r' | grep -Eiq '^allow:.*DELETE'; then
      echo "Admin 已启动，但未检测到 DELETE 接口，请检查构建内容。" >&2; exit 1;
    fi
    echo "Admin 重载完成，DELETE /api/interfaces/{id} 已可用。"
    echo "刷新平台页面即可；MySQL 数据、Gateway 和数据应用未重新初始化。"
    exit 0
  fi
  sleep 1
done
echo "Admin 启动超时，请查看 $LOG_FILE" >&2
exit 1
