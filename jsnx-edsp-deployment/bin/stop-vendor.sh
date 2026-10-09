#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$DEPLOY_DIR/var/run"

port_for_service() {
  case "$1" in
    vendor) echo 8090 ;;
    vendor-frontend) echo 5190 ;;
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

  if [[ -z "$pid" ]]; then
    echo "$name 没有由启动脚本记录的运行进程。"
    return
  fi

  if kill "$pid" 2>/dev/null; then
    for _ in {1..10}; do
      [[ -z "$port" ]] || ! port_is_open "$port" && break
      sleep 1
    done
    if [[ -n "$port" ]] && port_is_open "$port"; then
      echo "$name 未在宽限期内退出，发送强制停止信号。" >&2
      kill -9 "$pid" 2>/dev/null || true
    fi
    echo "$name 已停止（PID ${pid}）。"
  else
    echo "$name 无法停止 PID ${pid}，请检查进程权限。" >&2
  fi
  rm -f "$pid_file"
}

stop_process vendor-frontend
stop_process vendor
