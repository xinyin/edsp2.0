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

for command_name in mvn curl lsof; do
  command -v "$command_name" >/dev/null || { echo "缺少命令：$command_name" >&2; exit 1; }
done
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
export JAVA_HOME

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

echo "构建 Admin 及依赖组件（构建失败时保留当前服务）..."
(cd "$BACKEND_DIR" && mvn -q -pl platform-services/jsnx-edsp-data-admin -am package)
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
(
  cd "$MODULE_DIR"
  nohup env SERVER_PORT="$PORT" "$JAVA_HOME/bin/java" -jar "$JAR" >"$LOG_FILE" 2>&1 < /dev/null &
  echo $! >"$PID_FILE"
)
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
