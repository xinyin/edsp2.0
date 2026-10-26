#!/usr/bin/env bash
# Persist one local encryption key shared by Admin and independent data applications.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_ENV="$DEPLOY_DIR/var/nacos/local.env"
[[ -f "$LOCAL_ENV" ]] || { echo "未找到本地配置：$LOCAL_ENV" >&2; exit 1; }
if [[ -n "${EDSP_DATABASE_CREDENTIAL_KEY:-}" ]]; then exit 0; fi
set -a
source "$LOCAL_ENV"
set +a
if [[ -n "${EDSP_DATABASE_CREDENTIAL_KEY:-}" ]]; then exit 0; fi
command -v openssl >/dev/null || { echo "缺少 openssl，无法生成数据库密码加密密钥" >&2; exit 1; }
umask 077
printf '\nEDSP_DATABASE_CREDENTIAL_KEY=%s\n' "$(openssl rand -base64 32)" >> "$LOCAL_ENV"
chmod 600 "$LOCAL_ENV"
