#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$DEPLOY_DIR/var/run"

port_for_service() {
  case "$1" in
    frontend) echo 5173 ;;
    gateway) echo 8080 ;;
    admin) echo 8081 ;;
    capability-runtime|capability-service) echo 8091 ;;
    observability) echo 8083 ;;
    esb-adapter) echo 8084 ;;
    data-enterprise) echo 8181 ;;
    data-antifraud) echo 8183 ;;
    data-credit) echo 8182 ;;
    data-lhzx) echo 8191 ;;
    *) return 1 ;;
  esac
}

port_is_open() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
pid_owns_port() {
  local pid="$1" port="$2"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null &&
    lsof -nP -a -p "$pid" -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

stop_process() {
  local name="$1"
  local pid_file="$RUN_DIR/$name.pid"
  local port="$(port_for_service "$name" || true)"
  local pid=""

  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if [[ -n "$port" ]] && ! pid_owns_port "$pid" "$port"; then
      echo "$name 的 PID 文件已失效，忽略旧 PID ${pid}。"
      rm -f "$pid_file"
      pid=""
    fi
  fi
  if [[ -z "$pid" && "${STOP_ORPHANED:-0}" == "1" && -n "$port" ]]; then
    pid="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN | head -n 1 || true)"
    [[ -n "$pid" ]] && echo "$name 未找到 PID 文件，按端口 $port 回收残留进程（PID ${pid}）"
  fi

  [[ -n "$pid" ]] || { echo "$name 未由脚本启动"; return; }

  if kill "$pid" 2>/dev/null; then
    for _ in {1..15}; do
      [[ -z "$port" ]] || ! port_is_open "$port" && break
      sleep 1
    done
    if [[ -n "$port" ]] && port_is_open "$port"; then
      echo "$name 未在宽限期内退出，发送强制停止信号。" >&2
      kill -9 "$pid" 2>/dev/null || true
    fi
    echo "$name 已停止（PID ${pid}）"
  else
    echo "$name 无法停止 PID ${pid}，请检查进程权限。" >&2
  fi
  rm -f "$pid_file"
}

for service in frontend gateway esb-adapter observability capability-service capability-runtime data-lhzx data-credit data-antifraud data-enterprise admin backend; do
  stop_process "$service"
done
