#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOYMENT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEMO_DIR="$(cd "$DEPLOYMENT_DIR/.." && pwd)"
PLATFORM_SCHEMA="$DEMO_DIR/jsnx-edsp-platform-backend/platform-services/jsnx-edsp-data-admin/src/main/resources/db/schema.sql"
VENDOR_SCHEMA="$DEMO_DIR/jsnx-edsp-vendor-backend/src/main/resources/db/schema.sql"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"

if [[ "${1:-}" != "--yes" ]]; then
  echo "该操作会永久清空 edsp_demo 和 vendor_demo 中的全部测试业务数据。"
  echo "保留内容：数据库结构，以及代码内置操作账号 admin / admin123。"
  echo "执行方式：$0 --yes"
  exit 2
fi

MYSQL_ARGS=(-h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME")
[[ -n "$DB_PASSWORD" ]] && MYSQL_ARGS+=("-p$DB_PASSWORD")

running_ports=()
for port in 8080 8081 8083 8084 8090 8181 8182 8183; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    running_ports+=("$port")
  fi
done
if (( ${#running_ports[@]} > 0 )); then
  echo "检测到业务服务仍在运行（端口：${running_ports[*]}）。" >&2
  echo "请分别执行 $SCRIPT_DIR/stop-demo.sh 和 $SCRIPT_DIR/stop-vendor.sh，再重新执行清理。" >&2
  exit 1
fi

echo "核对目标数据库..."
mysql "${MYSQL_ARGS[@]}" -e "source $PLATFORM_SCHEMA; source $VENDOR_SCHEMA;"
mysql "${MYSQL_ARGS[@]}" -N -e "
SELECT IF(EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name='edsp_demo'),'edsp_demo:OK','edsp_demo:MISSING');
SELECT IF(EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name='vendor_demo'),'vendor_demo:OK','vendor_demo:MISSING');
"

echo "清理外部数据平台测试数据..."
mysql "${MYSQL_ARGS[@]}" <<'SQL'
USE edsp_demo;
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM api_call_node_log;
DELETE FROM demo_async_event;
DELETE FROM usage_record;
DELETE FROM billing_rule;
DELETE FROM api_authorization;
DELETE FROM api_client;
DELETE FROM interface_lifecycle_record;
DELETE FROM release_plan_snapshot;
DELETE FROM release_approval;
DELETE FROM release_record;
DELETE FROM vendor_mock_config;
DELETE FROM response_code_mapping;
DELETE FROM api_security_policy;
DELETE FROM interface_validation_record;
DELETE FROM field_mapping;
DELETE FROM published_route;
DELETE FROM api_interface;
DELETE FROM resource_api;
DELETE FROM data_source;
DELETE FROM provider_account;
DELETE FROM data_provider;
SET FOREIGN_KEY_CHECKS=1;

ALTER TABLE api_call_node_log AUTO_INCREMENT=1;
ALTER TABLE demo_async_event AUTO_INCREMENT=1;
ALTER TABLE usage_record AUTO_INCREMENT=1;
ALTER TABLE billing_rule AUTO_INCREMENT=1;
ALTER TABLE api_authorization AUTO_INCREMENT=1;
ALTER TABLE api_client AUTO_INCREMENT=1;
ALTER TABLE interface_lifecycle_record AUTO_INCREMENT=1;
ALTER TABLE release_plan_snapshot AUTO_INCREMENT=1;
ALTER TABLE release_approval AUTO_INCREMENT=1;
ALTER TABLE release_record AUTO_INCREMENT=1;
ALTER TABLE vendor_mock_config AUTO_INCREMENT=1;
ALTER TABLE response_code_mapping AUTO_INCREMENT=1;
ALTER TABLE interface_validation_record AUTO_INCREMENT=1;
ALTER TABLE field_mapping AUTO_INCREMENT=1;
ALTER TABLE api_interface AUTO_INCREMENT=1;
ALTER TABLE resource_api AUTO_INCREMENT=1;
ALTER TABLE data_source AUTO_INCREMENT=1;
ALTER TABLE provider_account AUTO_INCREMENT=1;
ALTER TABLE data_provider AUTO_INCREMENT=1;
SQL

echo "清理上游供应商模拟测试数据..."
mysql "${MYSQL_ARGS[@]}" <<'SQL'
USE vendor_demo;
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM vendor_api_definition;
DELETE FROM vendor_enterprise_record;
DELETE FROM vendor_data_source;
SET FOREIGN_KEY_CHECKS=1;

ALTER TABLE vendor_api_definition AUTO_INCREMENT=1;
ALTER TABLE vendor_enterprise_record AUTO_INCREMENT=1;
ALTER TABLE vendor_data_source AUTO_INCREMENT=1;
SQL

echo "核对清理结果..."
mysql "${MYSQL_ARGS[@]}" -N -e "
SELECT 'edsp_demo.data_provider',COUNT(*) FROM edsp_demo.data_provider
UNION ALL SELECT 'edsp_demo.data_source',COUNT(*) FROM edsp_demo.data_source
UNION ALL SELECT 'edsp_demo.api_interface',COUNT(*) FROM edsp_demo.api_interface
UNION ALL SELECT 'edsp_demo.resource_api',COUNT(*) FROM edsp_demo.resource_api
UNION ALL SELECT 'edsp_demo.interface_validation_record',COUNT(*) FROM edsp_demo.interface_validation_record
UNION ALL SELECT 'edsp_demo.provider_account',COUNT(*) FROM edsp_demo.provider_account
UNION ALL SELECT 'edsp_demo.api_client',COUNT(*) FROM edsp_demo.api_client
UNION ALL SELECT 'edsp_demo.usage_record',COUNT(*) FROM edsp_demo.usage_record
UNION ALL SELECT 'vendor_demo.vendor_data_source',COUNT(*) FROM vendor_demo.vendor_data_source
UNION ALL SELECT 'vendor_demo.vendor_api_definition',COUNT(*) FROM vendor_demo.vendor_api_definition
UNION ALL SELECT 'vendor_demo.vendor_enterprise_record',COUNT(*) FROM vendor_demo.vendor_enterprise_record;
"

echo "测试数据已清空。"
echo "保留操作账号：admin / admin123（代码内置，不在数据库中）。"
echo "启动外数平台：cd $DEPLOYMENT_DIR && ./bin/start-demo.sh"
echo "启动供应商模拟平台：cd $DEPLOYMENT_DIR && ./bin/start-vendor.sh"
echo "如需恢复供应商演示样例：SEED_DEMO_DATA=1 ./bin/start-vendor.sh"
