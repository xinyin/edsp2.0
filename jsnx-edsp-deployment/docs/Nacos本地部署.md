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

平台应用现在以 Nacos 作为运行配置的唯一集中来源。应用内 `application.yml` 只保留应用名、配置 Data ID、Nacos 地址、命名空间、Group 和鉴权参数；服务端口、数据库、Kafka、Redis、依赖服务地址、超时、Gateway 静态路由和日志参数均维护在 Nacos。接口映射、发布版本、授权、动态路由和执行计划仍是平台业务数据，保存在 `edsp_demo`，不迁移到 Nacos。

Git 中的基准配置位于 `config/nacos/data/`。首次部署先同步配置：

```bash
./bin/sync-config-to-nacos.sh

# 仅预览，不写入
DRY_RUN=1 ./bin/sync-config-to-nacos.sh

# Nacos 已存在不同内容时，明确覆盖
FORCE=1 ./bin/sync-config-to-nacos.sh

# 启动前只检查 9 个 Data ID 是否存在
CHECK_ONLY=1 ./bin/sync-config-to-nacos.sh
```

同步脚本默认不会覆盖控制台中已修改的不同配置，避免误删生产调整。只有 `FORCE=1` 才以 Git 基准覆盖 Nacos；脚本不输出账号、密码或访问令牌。

首次迁移或修改了启动阶段配置后，需要重启业务应用使其建立 Nacos 连接：

```bash
./bin/stop-platform.sh
./bin/start-platform.sh
```

本地脚本从被 Git 忽略的 `var/nacos/local.env` 加载凭证。正式部署应通过进程环境变量或凭证管理系统注入独立的最小权限应用账号，不能使用管理员账号。`start-platform.sh` 遇到已运行应用会跳过，因此切换配置中心或修改启动阶段配置时必须先停止旧进程。

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
| 可选复杂能力服务 | `jsnx-edsp-capability-runtime.yaml` |

在控制台“配置管理 → 配置列表”维护这些配置，保存后落入 `edsp_nacos.config_info`，历史进入 `his_config_info`。配置导入开启 `refreshEnabled=true`，但是否热更新仍取决于具体 Bean 的绑定方式：服务端口、数据源连接池、Kafka 客户端、构造器注入参数和 Gateway RouteDefinition 等启动阶段对象默认需要重启；平台业务配置仍按现有运行计划发布机制实时生效。不能把“配置已发布”理解为所有 Java 对象都会自动重建。

Nacos 提供应用配置管理和服务注册发现；平台接口映射、发布版本和 `apiCode → 数据源应用` 动态路由仍使用现有平台业务库和运行计划机制。本次迁移不改变 Gateway 的路由策略，也不增加 Spring Cloud LoadBalancer。

本地单机配置用于开发与联调；行内生产高可用部署需使用独立规划的 Nacos 集群和配置数据库。

## 本次部署验证

2026-10-10 本机验收：JDK 17、MySQL 9.3.0，健康检查通过，控制台 HTTP 200，实际状态 `auth_enabled=true`；未鉴权配置读取 HTTP 403，管理员登录正常。配置写入后在 `edsp_nacos.config_info` 可查，正常停止与重启后仍可读取；鉴权下服务注册与实例发现通过。测试配置和临时服务实例已清理。重复安装保留已有数据库，重复启动复用已记录且健康的进程，脚本语法和 Git 差异检查通过。

同日运行配置迁移验收：9 个 YAML Data ID 发布到 `public / DEFAULT_GROUP`，内容读取与 Git 基准一致，实际存储在 `edsp_nacos.config_info`，类型为 `yaml`。8 个常用后端应用重载成功，每个应用日志均确认 `Load config[...] success`、配置监听和 Nacos 服务注册完成；Gateway 动态路由快照、算法目录转发、数据应用联调入口和 ESB 模拟队列检查通过。重载构建执行现有测试，49 项通过，无失败。可选复杂能力服务完成构建和配置发布，默认不启动。业务数据库未重新初始化，用户数据保留。

本地启动脚本继续使用既定端口检查和管理进程。因此如在 Nacos 修改 `server.port`，也需同步相应启停脚本中的端口约定；监听端口不能通过配置刷新原地切换。供应商模拟工程和前端构建配置属于独立工程，本次集中管理范围为平台后端 9 个模块。
