#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/nacos-common.sh"
for required in curl tar shasum mysql openssl; do
  command -v "$required" >/dev/null || { echo "缺少命令：$required" >&2; exit 1; }
done
mkdir -p "$NACOS_LOCAL_DIR/downloads"
archive="$NACOS_LOCAL_DIR/downloads/nacos-server-$NACOS_VERSION.tar.gz"
if [[ ! -f "$NACOS_HOME/target/nacos-server.jar" ]]; then
  if [[ ! -f "$archive" ]] || [[ "$(shasum -a 256 "$archive" | awk '{print $1}')" != "$NACOS_SHA256" ]]; then
    curl -fL --retry 2 --connect-timeout 15 --max-time 1800 \
      -o "$archive" "https://github.com/alibaba/nacos/releases/download/$NACOS_VERSION/nacos-server-$NACOS_VERSION.tar.gz"
  fi
  actual_sha="$(shasum -a 256 "$archive" | awk '{print $1}')"
  [[ "$actual_sha" == "$NACOS_SHA256" ]] || { echo "安装包 SHA256 校验失败，停止安装。" >&2; exit 1; }
  tar -xzf "$archive" -C "$NACOS_LOCAL_DIR"
fi
if [[ ! -f "$NACOS_LOCAL_DIR/local.env" ]]; then
  # Runtime-generated credentials are private and excluded from Git.
  umask 077
  {
    printf 'NACOS_ADMIN_USERNAME=nacos\n'
    printf 'NACOS_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 12)"
    printf 'NACOS_TOKEN_SECRET=%s\n' "$(openssl rand -base64 48 | tr -d '\n')"
    printf 'NACOS_IDENTITY_VALUE=%s\n' "$(openssl rand -hex 24)"
  } > "$NACOS_LOCAL_DIR/local.env"
fi
nacos_load_env
[[ "$NACOS_DB_NAME" =~ ^[a-zA-Z0-9_]+$ ]] || { echo "数据库名称只能包含字母、数字、下划线。" >&2; exit 1; }
mysql_args=(--protocol=TCP -h "$NACOS_DB_HOST" -P "$NACOS_DB_PORT" -u "$NACOS_DB_USERNAME")
export MYSQL_PWD="$NACOS_DB_PASSWORD"
mysql "${mysql_args[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$NACOS_DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
table_count="$(mysql "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$NACOS_DB_NAME';")"
if [[ "$table_count" == 0 ]]; then
  mysql "${mysql_args[@]}" "$NACOS_DB_NAME" < "$NACOS_HOME/conf/mysql-schema.sql"
  echo "已初始化 Nacos 配置库：$NACOS_DB_NAME"
else
  required_count="$(mysql "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$NACOS_DB_NAME' AND table_name IN ('config_info','config_info_gray','his_config_info','users','roles','permissions');")"
  [[ "$required_count" == 6 ]] || { echo "现有数据库结构不完整，请检查；不会覆盖已有数据。" >&2; exit 1; }
  echo "保留已有 Nacos 数据库：$NACOS_DB_NAME"
fi
unset MYSQL_PWD
echo "Nacos $NACOS_VERSION 安装完成：$NACOS_HOME"
echo "登录账号 nacos；初始密码见 $NACOS_LOCAL_DIR/local.env 中 NACOS_ADMIN_PASSWORD。"
