#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"

check_url() {
  local name="$1" url="$2"
  if curl --fail --silent --show-error --max-time 5 "$url" >/dev/null; then
    echo "[OK] $name $url"
  else
    echo "[FAIL] $name $url" >&2
    return 1
  fi
}

echo "检查 EDSP 2.0 Demo 服务..."
check_url "供应商后端" "http://127.0.0.1:8090/actuator/health"
check_url "Admin" "http://127.0.0.1:8081/actuator/health"
check_url "Observability" "http://127.0.0.1:8083/actuator/health"
check_url "企业数据应用" "http://127.0.0.1:8181/actuator/health"
check_url "征信数据应用" "http://127.0.0.1:8182/actuator/health"
check_url "百融反欺诈应用" "http://127.0.0.1:8183/actuator/health"
check_url "ESB Adapter" "http://127.0.0.1:8084/actuator/health"
check_url "Gateway" "http://127.0.0.1:8080/actuator/health"
check_url "平台前端" "http://127.0.0.1:5173/"
check_url "供应商前端" "http://127.0.0.1:5190/"

echo
echo "执行 XML -> ESB -> Gateway -> 数据应用 -> 供应商全链路..."
"$DEPLOY_DIR/bin/run-full-demo.sh"

MYSQL_ARGS=(-h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME")
[[ -n "$DB_PASSWORD" ]] && MYSQL_ARGS+=("-p$DB_PASSWORD")

echo
echo "核对 MySQL 发布计划、异步事件和调用明细..."
mysql "${MYSQL_ARGS[@]}" -D edsp_demo -e "
SELECT interface_code,status,published_version FROM api_interface ORDER BY id;
SELECT COUNT(*) AS plan_snapshot_count FROM release_plan_snapshot;
SELECT event_type,COUNT(*) AS event_count FROM demo_async_event GROUP BY event_type ORDER BY event_type;
SELECT trace_id,result_status,standard_code,billable,amount,called_at FROM usage_record ORDER BY id DESC LIMIT 10;
"

echo
echo "EDSP 2.0 Demo 验证完成。"
