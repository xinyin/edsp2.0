#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/nacos-common.sh"
for required in curl lsof mysql jq; do
  command -v "$required" >/dev/null || { echo "缺少命令：$required" >&2; exit 1; }
done
if pid="$(nacos_owned_pid)"; then
  curl -fsS --connect-timeout 1 --max-time 3 "$NACOS_BASE_URL/v1/console/health/readiness" >/dev/null || {
    echo "Nacos 进程存在但尚未就绪，请查看 $NACOS_LOG_FILE" >&2; exit 1;
  }
  echo "Nacos 已在运行（PID ${pid}）：$NACOS_BASE_URL/"
  exit 0
fi
for port in 8848 9848 9849 7848; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 $port 已被其他进程占用，不会终止该进程。" >&2
    exit 1
  fi
done
[[ -f "$NACOS_HOME/target/nacos-server.jar" && -f "$NACOS_LOCAL_DIR/local.env" ]] || {
  echo "请先执行 $NACOS_DEPLOY_DIR/bin/install-nacos.sh" >&2; exit 1;
}
nacos_load_env
NACOS_JAVA_HOME="${NACOS_JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
[[ -x "$NACOS_JAVA_HOME/bin/java" ]] || { echo "请安装 JDK 17 或设置 NACOS_JAVA_HOME。" >&2; exit 1; }
"$NACOS_JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "17\.' || {
  echo "Nacos 本地脚本要求 JDK 17。" >&2; exit 1;
}
mkdir -p "$(dirname "$NACOS_PID_FILE")" "$(dirname "$NACOS_LOG_FILE")"
# Nacos's internal loader resolves application.properties relative to a directory.
# Use the generated conf directory for both Spring and Nacos's refresh watcher.
cp "$NACOS_DEPLOY_DIR/config/nacos/application.properties" "$NACOS_HOME/conf/application.properties"
(
  cd "$NACOS_HOME"
  nohup "$NACOS_JAVA_HOME/bin/java" -Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m \
    --add-opens=java.base/java.io=ALL-UNNAMED -Dsun.io.useCanonCaches=false \
    -Dnacos.standalone=true -Dnacos.home="$NACOS_HOME" -Dfile.encoding=UTF-8 \
    -Dloader.path="$NACOS_HOME/plugins,$NACOS_HOME/plugins/health,$NACOS_HOME/plugins/cmdb,$NACOS_HOME/plugins/selector" \
    -jar "$NACOS_HOME/target/nacos-server.jar" \
    --spring.config.additional-location="file:$NACOS_HOME/conf/" \
    --logging.config="$NACOS_HOME/conf/nacos-logback.xml" \
    > "$NACOS_LOG_FILE" 2>&1 < /dev/null &
  echo $! > "$NACOS_PID_FILE"
)
for _ in {1..90}; do
  if curl -fsS --connect-timeout 1 --max-time 2 "$NACOS_BASE_URL/v1/console/health/readiness" >/dev/null 2>&1; then
    # A fresh database has no admin. The server hashes the initial password.
    export MYSQL_PWD="$NACOS_DB_PASSWORD"
    admins="$(mysql --protocol=TCP -h "$NACOS_DB_HOST" -P "$NACOS_DB_PORT" -u "$NACOS_DB_USERNAME" "$NACOS_DB_NAME" -N -e "SELECT COUNT(*) FROM users WHERE username='nacos';")"
    unset MYSQL_PWD
    if [[ "$admins" == 0 ]]; then
      curl -fsS --connect-timeout 2 --max-time 10 -X POST \
        --data-urlencode "password=$NACOS_ADMIN_PASSWORD" "$NACOS_BASE_URL/v1/auth/users/admin" >/dev/null
    fi
    auth_enabled="$(curl -fsS --max-time 5 "$NACOS_BASE_URL/v1/console/server/state" | jq -r .auth_enabled)"
    [[ "$auth_enabled" == true ]] || { echo "Nacos 鉴权未生效，不会报告部署成功。" >&2; exit 1; }
    # The user/role cache is loaded asynchronously after web readiness.
    login_ready=false
    for _ in {1..30}; do
      if curl -fsS --max-time 3 -X POST --data-urlencode "username=$NACOS_ADMIN_USERNAME" \
        --data-urlencode "password=$NACOS_ADMIN_PASSWORD" "$NACOS_BASE_URL/v1/auth/login" 2>/dev/null | jq -e '.accessToken' >/dev/null; then
        login_ready=true
        break
      fi
      sleep 1
    done
    [[ "$login_ready" == true ]] || {
      echo "Nacos 已就绪但本地凭证登录失败，请同步 var/nacos/local.env 的密码。" >&2; exit 1;
    }
    echo "Nacos $NACOS_VERSION 已就绪：$NACOS_BASE_URL/"
    echo "账号：nacos；初始密码：var/nacos/local.env 中 NACOS_ADMIN_PASSWORD"
    exit 0
  fi
  nacos_owned_pid >/dev/null || { echo "Nacos 进程已退出，请查看 $NACOS_LOG_FILE" >&2; tail -35 "$NACOS_LOG_FILE"; exit 1; }
  sleep 1
done
echo "Nacos 未在就绪期限内完成启动，请查看 $NACOS_LOG_FILE；不会误报启动成功。" >&2
exit 1
