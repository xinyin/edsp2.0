#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/nacos-common.sh"
pid="$(nacos_owned_pid)" || { echo "本脚本部署的 Nacos 未运行。"; exit 1; }
echo "Nacos PID：$pid"
curl -fsS --connect-timeout 2 --max-time 5 "$NACOS_BASE_URL/v1/console/health/readiness"
printf '\n控制台：%s/\n' "$NACOS_BASE_URL"
lsof -nP -a -p "$pid" -iTCP -sTCP:LISTEN
