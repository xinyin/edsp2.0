#!/usr/bin/env bash
# Stop only a provider application launched by start-data-application.sh.
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
PID_FILE="$DEPLOY_DIR/var/run/$MODULE_NAME.pid"
if [[ ! -f "$PID_FILE" ]]; then
  echo "$MODULE_NAME 没有由启动脚本管理的进程。"
  exit 0
fi

pid="$(<"$PID_FILE")"
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then
  echo "$PID_FILE 记录的 PID 无效，拒绝停止进程。" >&2
  exit 1
fi
if ! kill -0 "$pid" 2>/dev/null; then
  echo "$MODULE_NAME 的进程已退出，移除失效 PID 记录。"
  rm -f "$PID_FILE"
  exit 0
fi

command_line="$(ps -p "$pid" -o command= || true)"
working_directory="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' || true)"
if [[ "$command_line" != *"$JAR"* || "$working_directory" != "$MODULE_DIR" ]] ||
   ! lsof -nP -a -p "$pid" -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "PID $pid 的 JAR、工作目录或监听端口与 $MODULE_NAME 不匹配，拒绝停止。" >&2
  exit 1
fi

kill -TERM "$pid"
for _ in {1..30}; do
  if ! kill -0 "$pid" 2>/dev/null ||
     ! lsof -nP -a -p "$pid" -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    rm -f "$PID_FILE"
    echo "$MODULE_NAME 已停止。"
    exit 0
  fi
  sleep 1
done
echo "$MODULE_NAME 未在 30 秒内退出；保留 PID 记录，请检查进程后手动处理。" >&2
exit 1
