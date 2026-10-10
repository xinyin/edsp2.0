# Nacos 本地部署

本工程使用 Nacos 2.5.2 独立 Java 进程，JDK 17，单机模式，不使用容器。现有 Spring Cloud Alibaba 2023.0.3.2 使用 Nacos Client 2.4.2，保持在 Nacos 2.x 协议体系内。

启动脚本将堆限制为 512 MB，并为 Nacos 内置 Tomcat 添加 JDK 17 所需的 `java.io` 模块访问参数，关闭规范路径缓存；参数仅作用于 Nacos 进程，不修改全局 Java 配置。

## 目录和存储

所有命令均从以下目录执行：

```bash
cd /Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment
```

| 内容 | 位置 |
| --- | --- |
| 安装包和解压程序 | `var/nacos/` |
| 配置模板 | `config/nacos/application.properties` |
| 启动时从模板生成的实际配置 | `var/nacos/nacos/conf/application.properties` |
| 本地随机生成的密码和鉴权密钥 | `var/nacos/local.env`，权限 600，不提交 Git |
| 启动日志 | `var/logs/nacos.log` |
| Nacos 自身日志、运行数据 | `var/nacos/nacos/logs/`、`var/nacos/nacos/data/` |
| PID | `var/run/nacos.pid` |
| 配置及配置历史、用户、角色和权限 | 本地 MySQL 的 `edsp_nacos` 库 |

业务库 `edsp_demo`、供应商库 `vendor_demo` 与 Nacos 数据库分开。此部署显式启用 MySQL，不使用内置 Derby 保存配置。临时服务实例由客户端注册、心跳和重连维护，不能把 MySQL 配置持久化理解成所有服务实例都写入 MySQL。

HTTP 控制台监听 `127.0.0.1:8848`。Nacos 2.5.2 的 gRPC 端口 9848/9849 由官方实现监听通配地址，已开启 Nacos 鉴权；`nacos.inetutils.ip-address` 是节点声明地址，不是 gRPC 绑定地址。脚本也检查内部端口 7848 是否冲突。

## 安装、启动和停止

```bash
# 首次安装：官方下载、校验 SHA256、创建独立库并导入官方结构
./bin/install-nacos.sh

./bin/start-nacos.sh
./bin/status-nacos.sh
./bin/stop-nacos.sh
```

安装需要本地 MySQL 启动，默认 TCP `127.0.0.1:3306`、用户 `root`、空密码。可以通过 `NACOS_DB_HOST`、`NACOS_DB_PORT`、`NACOS_DB_NAME`、`NACOS_DB_USERNAME`、`NACOS_DB_PASSWORD` 调整连接参数；安装和后续启动应使用相同参数，也可以将这些参数写入被 Git 忽略的 `var/nacos/local.env`。

安装脚本仅在目标库没有表时导入结构；已有结构会检查并保留，不清空配置。启停脚本仅操作自己记录且命令行校验通过的 Nacos PID，不按端口强行停止其他进程。停止 Nacos 不停止平台业务应用，不删除数据库。平台现有 `start-demo.sh`、`stop-demo.sh`、`reload-integration-stack.sh` 不管理本次独立安装的 Nacos。

## 控制台登录

控制台：<http://127.0.0.1:8848/nacos/>。

用户名：`nacos`。首次密码随机生成，可只读取密码这一项：

```bash
sed -n 's/^NACOS_ADMIN_PASSWORD=//p' var/nacos/local.env
```

首次启动通过官方初始化接口创建管理员，密码由 Nacos 加密保存。后续重启保留账号；不会重置控制台修改过的密码。若在控制台修改密码，应同步本地应用连接凭证。`local.env` 包含鉴权密钥，不应粘贴到代码、工单或日志中。

健康检查：

```bash
curl --fail http://127.0.0.1:8848/nacos/v1/console/health/readiness
tail -n 60 var/logs/nacos.log
```

## 与平台应用的关系

安装并启动 Nacos 后，当前已经运行的业务应用不会自动切换到 Nacos。业务应用默认 `NACOS_ENABLED=false`。在需要接入时，设置连接地址和鉴权凭证，并对需要接入的应用重新启动一次；客户端建立连接后，支持的动态配置变更不需要每次重启。

以下是接入现有整套本地平台的命令，执行停止命令会中断平台业务进程，因此本次安装不自动执行：

```bash
set -a
source var/nacos/local.env
set +a
export NACOS_ENABLED=true
export NACOS_SERVER_ADDR=127.0.0.1:8848
export SPRING_CLOUD_NACOS_CONFIG_USERNAME="$NACOS_ADMIN_USERNAME"
export SPRING_CLOUD_NACOS_CONFIG_PASSWORD="$NACOS_ADMIN_PASSWORD"
export SPRING_CLOUD_NACOS_DISCOVERY_USERNAME="$NACOS_ADMIN_USERNAME"
export SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD="$NACOS_ADMIN_PASSWORD"

./bin/stop-platform.sh
./bin/start-platform.sh
```

上述示例使用本地管理员供本机开发接入；正式部署应为应用建立最小权限的独立账号。`start-platform.sh` 遇到已经运行的应用会跳过，因此只给启动命令加环境变量不能改变旧进程配置。

现有应用默认配置命名空间为 `public`，Group 为 `DEFAULT_GROUP`，导入 Data ID 如下，配置格式使用 YAML：

| 应用 | Data ID |
| --- | --- |
| 管理服务 | `jsnx-edsp-data-admin.yaml` |
| Gateway | `jsnx-edsp-data-gateway.yaml` |
| ESB Adapter | `jsnx-edsp-esb-adapter.yaml` |
| 监控日志服务 | `jsnx-edsp-data-observability.yaml` |
| 企业工商应用 | `jsnx-edsp-data-enterprise.yaml` |
| 征信应用 | `jsnx-edsp-data-credit.yaml` |
| 百融反欺诈应用 | `jsnx-edsp-data-antifraud.yaml` |
| 联合征信应用 | `jsnx-edsp-data-source-lhzx.yaml` |

在控制台“配置管理 → 配置列表 → 新建配置”维护这些配置，保存后落入 `edsp_nacos.config_info`，历史进入 `his_config_info`。仅发布需要覆盖的参数，避免复制整份本地配置导致端口等参数被误覆盖。具体参数是否动态生效取决于应用的绑定方式和刷新处理，不是所有参数都能热更新，例如服务监听端口通常需要重启。

Nacos 提供应用配置管理和服务注册发现；平台接口映射、发布版本和 `apiCode → 数据源应用` 动态路由仍使用现有平台业务库和运行计划机制。本次部署不改变 Gateway 的路由策略，也不增加 Spring Cloud LoadBalancer。

本地单机配置用于开发与联调；行内生产高可用部署需使用独立规划的 Nacos 集群和配置数据库。

## 本次部署验证

2026-10-10 本机验收：JDK 17、MySQL 9.3.0，健康检查通过，控制台 HTTP 200，实际状态 `auth_enabled=true`；未鉴权配置读取 HTTP 403，管理员登录正常。配置写入后在 `edsp_nacos.config_info` 可查，正常停止与重启后仍可读取；鉴权下服务注册与实例发现通过。测试配置和临时服务实例已清理。重复安装保留已有数据库，重复启动复用已记录且健康的进程，脚本语法和 Git 差异检查通过。
