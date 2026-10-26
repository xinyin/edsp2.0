# EDSP 2.0 Demo 部署工程

本目录只保存跨工程启停脚本、运行日志、PID 和技术文档，不包含前后端源码。

## 同级工程

```text
demo/
├── jsnx-edsp-platform-backend/   # 平台后端 Maven 聚合工程
├── jsnx-edsp-platform-frontend/  # 平台 Vue 前端工程
├── jsnx-edsp-vendor-backend/     # 上游供应商后端工程
├── jsnx-edsp-vendor-frontend/    # 上游供应商 Vue 前端工程
└── jsnx-edsp-deployment/         # 本部署工程
```

## 模块说明

| 模块 | 端口 | 说明 |
| --- | ---: | --- |
| `jsnx-edsp-data-gateway` | 8080 | 统一入口、认证、授权、路由和入口追踪 |
| `jsnx-edsp-data-admin` | 8081 | 配置管理、审批、授权、计费和联调 |
| `jsnx-edsp-data-observability` | 8083 | 调用日志、链路追踪和监控运维 |
| `jsnx-edsp-esb-adapter` | 8084 | ESB XML/队列报文转换 |
| `jsnx-edsp-capability-runtime` | 8091 | 可选复杂能力服务，普通调用链不依赖 |
| `jsnx-edsp-data-enterprise` | 8181 | 企业工商数据源独立处理单元 |
| `jsnx-edsp-data-antifraud` | 8183 | 百融反欺诈数据源独立处理单元 |
| `jsnx-edsp-data-credit` | 8182 | 征信报告数据源独立处理单元 |
| `jsnx-edsp-data-source-lhzx` | 8191 | 联合征信（LHZX）数据源独立处理单元 |
| `jsnx-edsp-vendor-backend` | 8090 | 独立上游供应商模拟服务 |

每个后端服务都是独立 JVM 进程。单个数据源应用的配置、故障和发布不会直接影响其他数据源应用。

业务请求演示路径为 XML 请求 → 模拟 ESB 队列 → `jsnx-edsp-esb-adapter` 转 JSON → Gateway → 对应数据源应用 → 统一 JSON 响应。Gateway 只做统一入口治理和一级路由；业务数据应用各自完成计划加载、转换和上游调用。

五个目录可分别由 IntelliJ IDEA、VS Code 或其他工具独立打开、构建和交付。

## 服务端口

| 工程/服务 | 端口 |
|---|---:|
| 平台前端 | 5173 |
| Spring Cloud Gateway | 8080 |
| Admin 管理服务 | 8081 |
| Observability 可观测服务 | 8083 |
| ESB Adapter | 8084 |
| 企业工商数据应用 | 8181 |
| 百融反欺诈数据应用 | 8183 |
| 征信报告数据应用 | 8182 |
| 联合征信 LHZX 数据应用 | 8191 |
| 供应商后端 | 8090 |
| 供应商前端 | 5190 |

## 启停

独立 Nacos 本地部署：[`docs/Nacos本地部署.md`](docs/Nacos本地部署.md)。启停入口为 `bin/start-nacos.sh`、`bin/stop-nacos.sh`，状态检查为 `bin/status-nacos.sh`。平台运行配置已集中到 Nacos，`start-platform.sh` 会检查 Nacos 和 9 个必需 Data ID；Nacos 或配置缺失时不会启动半套平台。

配置模板位于 `config/nacos/data/`。首次安装或 Git 中配置模板发生变更后执行：

```bash
./bin/sync-config-to-nacos.sh

# Nacos 已有配置与 Git 模板不同时，明确确认后覆盖
FORCE=1 ./bin/sync-config-to-nacos.sh
```

日常配置可在 Nacos 控制台维护。`DRY_RUN=1` 仅预览，`CHECK_ONLY=1` 只检查必需 Data ID 是否存在，不覆盖控制台内容。

```bash
cd /Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment

./bin/start-demo.sh

./bin/stop-demo.sh
```

以上两个脚本只管理外部数据平台。供应商模拟平台是完全独立的应用，不会随外数平台启动、停止或失败回滚。

供应商模拟平台独立启停：

```bash
./bin/start-vendor.sh
./bin/stop-vendor.sh
```

两个平台均已独立启动后，可执行跨系统完整业务演示：

```bash
./bin/run-full-demo.sh
```

执行跨系统服务健康、全链路和数据库落库验收：

```bash
./bin/verify-demo.sh
```

`run-full-demo.sh` 和 `verify-demo.sh` 只执行联调或验收，不会隐式启停任一平台。完整演示流程、样例请求报文、命中/未命中场景和页面操作步骤见 [`DEMO_RUNBOOK.md`](DEMO_RUNBOOK.md)。

MySQL 默认连接为 `127.0.0.1:3306`、`root`、无密码。可使用 `DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD` 和 `DB_URL` 覆盖。

可选复杂能力服务默认不启动；需要单独演示时执行：

```bash
START_CAPABILITY_SERVICE=1 ./bin/start-demo.sh
```

日志保存在 `var/logs/`，PID 保存在 `var/run/`。

新增的独立数据应用在构建并配置 Nacos 后，从部署工程启动，例如：

```bash
./bin/start-data-application.sh jsnx-edsp-data-source-raex 18880
```

`9494` 资源方的本地监听端口为 `18883`，可直接运行：

```bash
./bin/start-provider-9494.sh
curl --fail http://127.0.0.1:18883/actuator/health
curl --fail http://127.0.0.1:18883/internal/adapter/info
```

后续新增资源方应用，以 `jsnx-edsp-data-provider-example`、端口 `18884` 为例：

1. 在“资源方管理”登记提供商，配置唯一应用名、监听端口和应用访问地址；生成工程及 Nacos 配置。将生成的模块纳入后端工程，父 `pom.xml` 的模块路径须为 `data-applications/jsnx-edsp-data-provider-example`（不加前导 `/`）。
2. 检查 Nacos 中同名 Data ID 已发布。若仅有工程内的 YAML，可从模块目录复制到 `jsnx-edsp-deployment/config/nacos/data/`，在部署工程执行 `./bin/sync-config-to-nacos.sh`。已有配置与文件不同时先核对差异，不直接覆盖。
3. 使用 JDK 17 构建、启动并验收：

```bash
cd ../jsnx-edsp-platform-backend
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -f data-applications/jsnx-edsp-data-provider-example/pom.xml clean verify
cd ../jsnx-edsp-deployment
./bin/start-data-application.sh jsnx-edsp-data-provider-example 18884
curl --fail http://127.0.0.1:18884/actuator/health
curl --fail http://127.0.0.1:18884/internal/adapter/info
```

4. 核对 `/internal/adapter/info` 的应用名和提供商编码，与页面路由一致，再执行数据库连接测试或上游 API 联调。

部署新版本时，先从流量入口摘除该实例，再停止旧进程、替换 JAR 并启动；本地可执行 `./bin/stop-data-application.sh jsnx-edsp-data-provider-example 18884`，随后运行相同的启动命令。启动脚本只检查应用身份和健康状态，不会自动替换已运行的旧 JAR。生产环境由进程管理器或部署流水线负责进程守护与滚动发布，平台总启动脚本不自动管理新增资源方应用。

脚本会进入应用模块目录启动 JAR，并检查 `/internal/adapter/info` 是否返回对应应用，而非仅判断端口是否监听。不要在本部署目录直接运行数据应用 JAR：这里的 `config/nacos/application.properties` 属于 Nacos 服务端，Spring Boot 从当前目录自动加载时会把数据应用的上下文路径误设为 `/nacos`，导致接入测试返回 404。

演示样例报文保存在 `fixtures/`：

ESB 样例统一采用 `Service / Service_Header / Service_Body` 生产 XML 契约；平台内部处理和 Kafka 事件保持结构化 JSON，最终响应由 Adapter 封装为 XML 写回 ESB。

- `vendor-enterprise-hit.json`：直接调用上游且命中企业记录；
- `vendor-enterprise-miss.json`：直接调用上游但未查得企业；
- `platform-enterprise-hit.json`：下游标准字段请求；
- `platform-enterprise-miss.json`：下游未命中场景请求；
- `esb-enterprise-hit.xml`：ESB 企业工商命中报文；
- `esb-enterprise-miss.xml`：ESB 企业工商未命中报文；
- `esb-credit-hit.xml`：ESB 个人征信命中报文；
- `esb-antifraud-hit.xml`：ESB 百融反欺诈命中报文。

## 独立构建

平台后端：

```bash
cd ../jsnx-edsp-platform-backend
mvn clean package -DskipTests
```

平台前端：

```bash
cd ../jsnx-edsp-platform-frontend
npm install
npm run build
```

供应商后端：

```bash
cd ../jsnx-edsp-vendor-backend
mvn clean package -DskipTests
```

供应商前端：

```bash
cd ../jsnx-edsp-vendor-frontend
npm install
npm run build
```

## 技术边界

- MySQL 是当前唯一持久化数据库。
- 平台依赖 Nacos 统一管理运行配置和服务注册；本地不要求真实 Redis、Kafka、ES 或容器环境。
- ESB 请求/响应队列由 `jsnx-edsp-esb-adapter` 内存队列模拟。
- Kafka 异步日志由非阻塞 HTTP 事件投递模拟；监控服务不可用时只允许丢日志，不阻断业务请求。
- 各应用的 `application.yml` 只保留 Nacos 启动引导参数；端口、数据库、Kafka、Redis、超时、Gateway 静态路由和平台运行参数由独立 Data ID 管理。Gateway 不引入 Spring Cloud LoadBalancer。
- 发布计划持久化到 MySQL，数据源应用按接口加载本地 PlanSnapshot，并通过 AtomicReference 原子切换。
- 公共能力服务仅用于按需复杂处理，普通字段映射、签名、加解密和响应标准化在目标数据源应用内执行。
- Elasticsearch、GoldenDB、Doris 当前不引入。
- 所有服务以独立 JVM 运行，不依赖容器。
