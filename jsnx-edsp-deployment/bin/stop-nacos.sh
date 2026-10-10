#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/nacos-common.sh"
if ! pid="$(nacos_owned_pid)"; then
  echo "没有由本脚本记录的 Nacos 进程；不会按端口终止其他应用。"
  exit 0
fi
kill "$pid"
for _ in {1..30}; do
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$NACOS_PID_FILE"
    echo "Nacos 已停止，配置数据保留在 MySQL。"
    exit 0
  fi
  sleep 1
done
echo "Nacos 尚未退出，保留 PID 文件，请检查日志后处理。" >&2
exit 1
