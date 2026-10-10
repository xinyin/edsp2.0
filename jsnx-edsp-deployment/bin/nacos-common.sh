#!/usr/bin/env bash
# Shared by the standalone Nacos scripts, not by the platform startup scripts.
NACOS_DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NACOS_LOCAL_DIR="$NACOS_DEPLOY_DIR/var/nacos"
NACOS_HOME="$NACOS_LOCAL_DIR/nacos"
NACOS_PID_FILE="$NACOS_DEPLOY_DIR/var/run/nacos.pid"
NACOS_LOG_FILE="$NACOS_DEPLOY_DIR/var/logs/nacos.log"
NACOS_BASE_URL="http://127.0.0.1:8848/nacos"
NACOS_VERSION=2.5.2
NACOS_SHA256=48e78acddf9394994b99e250276fd5deb2c7fa13fdfbdf255e27c039b9d7d294

nacos_owned_pid() {
  local pid command_line
  [[ -f "$NACOS_PID_FILE" ]] || return 1
  pid="$(<"$NACOS_PID_FILE")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  command_line="$(ps -p "$pid" -o command= 2>/dev/null)" || return 1
  [[ "$command_line" == *"-Dnacos.home=$NACOS_HOME"* && "$command_line" == *"nacos-server.jar"* ]] || return 1
  printf '%s\n' "$pid"
}

nacos_load_env() {
  if [[ -f "$NACOS_LOCAL_DIR/local.env" ]]; then
    set -a
    source "$NACOS_LOCAL_DIR/local.env"
    set +a
  fi
  export NACOS_DB_HOST="${NACOS_DB_HOST:-127.0.0.1}"
  export NACOS_DB_PORT="${NACOS_DB_PORT:-3306}"
  export NACOS_DB_NAME="${NACOS_DB_NAME:-edsp_nacos}"
  export NACOS_DB_USERNAME="${NACOS_DB_USERNAME:-root}"
  export NACOS_DB_PASSWORD="${NACOS_DB_PASSWORD:-}"
}
