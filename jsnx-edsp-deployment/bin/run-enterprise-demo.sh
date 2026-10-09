#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE_DIR="$BASE_DIR/fixtures"
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8080}"
ESB_URL="${ESB_URL:-http://127.0.0.1:8084}"
VENDOR_URL="${VENDOR_URL:-http://127.0.0.1:8090}"
APP_ID="${APP_ID:-CREDIT_RISK}"
API_KEY="${API_KEY:-demo-secret-key}"

pretty() {
  if command -v jq >/dev/null 2>&1; then jq .
  else cat
  fi
}

request() {
  local label="$1"; shift
  local response_file http_status curl_status
  response_file="$(mktemp)"
  echo
  echo "========== $label =========="
  set +e
  http_status="$(curl --silent --show-error --output "$response_file" --write-out '%{http_code}' "$@")"
  curl_status=$?
  set -e
  if [[ "$curl_status" -ne 0 ]]; then
    echo "curl 执行失败，退出码：$curl_status" >&2
  fi
  pretty <"$response_file"
  echo "HTTP $http_status"
  rm -f "$response_file"
}

echo "EDSP 2.0 多数据源业务全链路演示"
echo "Gateway: $GATEWAY_URL"
echo "Mock ESB: $ESB_URL"
echo "Vendor : $VENDOR_URL"

request "1. 上游供应商开放目录" \
  "$VENDOR_URL/open/catalog"

request "2. 直接调用上游：命中真实企业记录" \
  -H 'Content-Type: application/json' \
  --data-binary "@$FIXTURE_DIR/vendor-enterprise-hit.json" \
  "$VENDOR_URL/vendor/api/enterprise-basic"

request "3. 直接调用上游：不存在的企业返回未查得" \
  -H 'Content-Type: application/json' \
  --data-binary "@$FIXTURE_DIR/vendor-enterprise-miss.json" \
  "$VENDOR_URL/vendor/api/enterprise-basic"

request "4. 通过模拟 ESB 队列调用：XML -> Adapter -> Gateway -> 企业数据源" \
  -H "X-App-Id: $APP_ID" \
  -H "X-Api-Key: $API_KEY" \
  -H 'X-Trace-Id: DEMO-ENTERPRISE-HIT-001' \
  -H 'Content-Type: application/xml' \
  --data-binary "@$FIXTURE_DIR/esb-enterprise-hit.xml" \
  "$ESB_URL/esb/queue/request?servicePath=/api/v1/enterprise/basic"

request "5. 通过模拟 ESB 队列调用：企业不存在并返回统一未查得码" \
  -H "X-App-Id: $APP_ID" \
  -H "X-Api-Key: $API_KEY" \
  -H 'X-Trace-Id: DEMO-ENTERPRISE-MISS-001' \
  -H 'Content-Type: application/xml' \
  --data-binary "@$FIXTURE_DIR/esb-enterprise-miss.xml" \
  "$ESB_URL/esb/queue/request?servicePath=/api/v1/enterprise/basic"

request "6. 通过模拟 ESB 队列调用：百融反欺诈数据源" \
  -H "X-App-Id: $APP_ID" \
  -H "X-Api-Key: $API_KEY" \
  -H 'X-Trace-Id: DEMO-ANTIFRAUD-HIT-001' \
  -H 'Content-Type: application/xml' \
  --data-binary "@$FIXTURE_DIR/esb-antifraud-hit.xml" \
  "$ESB_URL/esb/queue/request?servicePath=/api/v1/antifraud/score"

request "7. 通过模拟 ESB 队列调用：个人征信独立数据源" \
  -H "X-App-Id: $APP_ID" \
  -H "X-Api-Key: $API_KEY" \
  -H 'X-Trace-Id: DEMO-CREDIT-HIT-001' \
  -H 'Content-Type: application/xml' \
  --data-binary "@$FIXTURE_DIR/esb-credit-hit.xml" \
  "$ESB_URL/esb/queue/request?servicePath=/api/v1/credit/report"

request "8. 未认证请求：Gateway 返回 401" \
  -H 'Content-Type: application/json' \
  --data-binary "@$FIXTURE_DIR/platform-enterprise-hit.json" \
  "$GATEWAY_URL/api/v1/enterprise/basic"

request "9. 已认证但未授权：MOBILE_BANK 返回 403" \
  -H 'X-App-Id: MOBILE_BANK' \
  -H "X-Api-Key: $API_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary "@$FIXTURE_DIR/platform-enterprise-hit.json" \
  "$GATEWAY_URL/api/v1/enterprise/basic"

request "10. 查询调用日志" \
  "$GATEWAY_URL/api/call-logs?interfaceCode=ENTERPRISE_BASIC"

request "11. 查询平台监控摘要" \
  "$GATEWAY_URL/api/monitor/summary"

request "12. 查询模拟 ESB 队列状态" \
  "$ESB_URL/esb/queue/status"

cat <<'EOF'

演示完成。可在平台前端继续查看：
  数据资源服务 -> 企业工商 / 个人征信 / 百融反欺诈数据源 -> API
  联调测试 -> ESB XML、请求转换、上游调用、响应标准化和完整链路
  调用日志 -> 按 Trace ID 查询 DEMO-ENTERPRISE-HIT-001
  监控运维 -> 调用量、成功率、节点状态和告警

热更新演示：
  1. 在供应商前端新增或修改 API；
  2. 在平台“供应商能力接入”刷新开放目录；
  3. 在 API 配置中修改映射或上游地址并保存草稿；
  4. 点击校验、提交审批、审批发布；
  5. 不重启数据应用，重新执行本脚本，验证新计划已生效。
EOF
