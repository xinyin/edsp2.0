#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_DIR="$DEPLOY_DIR/config/nacos/data"
NACOS_LOCAL_DIR="$DEPLOY_DIR/var/nacos"
LOCAL_ENV="$NACOS_LOCAL_DIR/local.env"

if [[ -f "$LOCAL_ENV" ]]; then
  # This file is generated locally, is mode 600, and is excluded from Git.
  set -a
  source "$LOCAL_ENV"
  set +a
fi

NACOS_BASE_URL="${NACOS_BASE_URL:-http://${NACOS_SERVER_ADDR:-127.0.0.1:8848}/nacos}"
NACOS_CONFIG_USERNAME="${NACOS_CONFIG_USERNAME:-${NACOS_USERNAME:-${NACOS_ADMIN_USERNAME:-}}}"
NACOS_CONFIG_PASSWORD="${NACOS_CONFIG_PASSWORD:-${NACOS_PASSWORD:-${NACOS_ADMIN_PASSWORD:-}}}"
NACOS_GROUP="${NACOS_GROUP:-DEFAULT_GROUP}"
NACOS_NAMESPACE_ID="${NACOS_NAMESPACE_ID:-${NACOS_NAMESPACE:-}}"
DRY_RUN="${DRY_RUN:-0}"
FORCE="${FORCE:-0}"
CHECK_ONLY="${CHECK_ONLY:-0}"

for required in curl jq shasum; do
  command -v "$required" >/dev/null 2>&1 || {
    echo "缺少命令：${required}，请先安装并加入 PATH。" >&2
    exit 1
  }
done
[[ -d "$CONFIG_DIR" ]] || { echo "未找到 Nacos 配置目录：$CONFIG_DIR" >&2; exit 1; }
[[ -n "$NACOS_CONFIG_USERNAME" && -n "$NACOS_CONFIG_PASSWORD" ]] || {
  echo "未找到 Nacos 配置账号。请检查 $LOCAL_ENV 或设置 NACOS_CONFIG_USERNAME/NACOS_CONFIG_PASSWORD。" >&2
  exit 1
}

curl -fsS --connect-timeout 2 --max-time 5 "$NACOS_BASE_URL/v1/console/health/readiness" >/dev/null || {
  echo "Nacos 未就绪：${NACOS_BASE_URL}，请先执行 bin/start-nacos.sh。" >&2
  exit 1
}

login_response="$(curl -fsS --connect-timeout 2 --max-time 10 -X POST \
  --data-urlencode "username=$NACOS_CONFIG_USERNAME" \
  --data-urlencode "password=$NACOS_CONFIG_PASSWORD" \
  "$NACOS_BASE_URL/v1/auth/login")"
access_token="$(jq -r '.accessToken // empty' <<<"$login_response")"
[[ -n "$access_token" ]] || { echo "Nacos 登录失败，未取得访问令牌。" >&2; exit 1; }

# Nacos treats an empty tenant as its built-in public namespace. Keeping the
# argument present also avoids an empty-array expansion under `set -u`.
namespace_args=(--data-urlencode "tenant=$NACOS_NAMESPACE_ID")

declare -a config_files=()
while IFS= read -r file; do config_files+=("$file"); done < <(find "$CONFIG_DIR" -maxdepth 1 -type f -name '*.yaml' | sort)
[[ "${#config_files[@]}" -gt 0 ]] || { echo "配置目录为空：$CONFIG_DIR" >&2; exit 1; }

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/edsp-nacos-sync.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

config_exists() {
  local data_id="$1" body_file="$2" status
  status="$(curl -sS --connect-timeout 2 --max-time 10 -o "$body_file" -w '%{http_code}' \
    -G "$NACOS_BASE_URL/v1/cs/configs" \
    --data-urlencode "dataId=$data_id" \
    --data-urlencode "group=$NACOS_GROUP" \
    "${namespace_args[@]}" \
    --data-urlencode "accessToken=$access_token")" || return 2
  case "$status" in
    200) return 0 ;;
    404) return 1 ;;
    *) echo "[error] ${data_id} 读取失败（HTTP ${status}），不执行写入。" >&2; return 2 ;;
  esac
}

publish_config() {
  local file="$1" data_id existing_file existing_sha local_sha read_status
  data_id="$(basename "$file")"
  existing_file="$tmp_dir/$data_id.current"
  local_sha="$(shasum -a 256 "$file" | awk '{print $1}')"

  if config_exists "$data_id" "$existing_file"; then
    existing_sha="$(shasum -a 256 "$existing_file" | awk '{print $1}')"
    if [[ "$existing_sha" == "$local_sha" ]]; then
      echo "[unchanged] $data_id"
      return 0
    fi
    if [[ "$CHECK_ONLY" == "1" ]]; then
      echo "[present]   ${data_id}（Nacos 内容由控制台维护，未覆盖）"
      return 0
    fi
    if [[ "$FORCE" != "1" ]]; then
      echo "[conflict]  $data_id 已存在且内容不同；如确认以 Git 配置覆盖，请使用 FORCE=1。" >&2
      return 2
    fi
    echo "[update]    $data_id"
  else
    read_status=$?
    [[ "$read_status" == "1" ]] || return 2
    [[ "$CHECK_ONLY" != "1" ]] || {
      echo "[missing]   ${data_id}（请先执行同步脚本）" >&2
      return 2
    }
    echo "[create]    $data_id"
  fi

  [[ "$DRY_RUN" == "1" ]] && return 0
  if ! curl -fsS --connect-timeout 2 --max-time 15 -X POST \
    "$NACOS_BASE_URL/v1/cs/configs" \
    --data-urlencode "dataId=$data_id" \
    --data-urlencode "group=$NACOS_GROUP" \
    "${namespace_args[@]}" \
    --data-urlencode "content@$file" \
    --data-urlencode "type=yaml" \
    --data-urlencode "accessToken=$access_token" | jq -e 'if . == true or . == "true" then . else error("Nacos 返回失败") end' >/dev/null; then
    echo "[error] $data_id 发布失败。" >&2
    return 2
  fi
  config_exists "$data_id" "$existing_file" || return 2
  existing_sha="$(shasum -a 256 "$existing_file" | awk '{print $1}')"
  [[ "$existing_sha" == "$local_sha" ]] || {
    echo "[error] $data_id 发布后内容校验不一致。" >&2
    return 2
  }
  echo "[verified]  $data_id"
}

failed=0
for file in "${config_files[@]}"; do
  publish_config "$file" || failed=1
done

if [[ "$failed" == "1" ]]; then
  echo "Nacos 配置同步未完成。" >&2
  exit 1
fi
if [[ "$CHECK_ONLY" == "1" ]]; then
  echo "Nacos 配置检查通过。"
elif [[ "$DRY_RUN" == "1" ]]; then
  echo "预览完成，未写入 Nacos。"
else
  echo "Nacos 配置同步完成：${#config_files[@]} 个 Data ID，Group=${NACOS_GROUP}。"
fi
