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

独立 Nacos 本地部署：[`docs/Nacos本地部署.md`](docs/Nacos本地部署.md)。启停入口为 `bin/start-nacos.sh`、`bin/stop-nacos.sh`，状态检查为 `bin/status-nacos.sh`，与平台启停分开管理。

```bash
cd /Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment

./bin/start-demo.sh

./bin/stop-demo.sh
```

一键执行完整业务演示：

```bash
./bin/run-full-demo.sh
```

执行服务健康、全链路和数据库落库验收：

```bash
./bin/verify-demo.sh
```

一键启动会先启动独立的上游供应商模拟应用，再启动外部数据平台；一键停止会按相反顺序停止两组应用。完整演示流程、样例请求报文、命中/未命中场景和页面操作步骤见 [`DEMO_RUNBOOK.md`](DEMO_RUNBOOK.md)。

如需分组启停：

```bash
./bin/start-vendor.sh
./bin/start-platform.sh

./bin/stop-platform.sh
./bin/stop-vendor.sh
```

MySQL 默认连接为 `127.0.0.1:3306`、`root`、无密码。可使用 `DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD` 和 `DB_URL` 覆盖。

可选复杂能力服务默认不启动；需要单独演示时执行：

```bash
START_CAPABILITY_SERVICE=1 ./bin/start-demo.sh
```

日志保存在 `var/logs/`，PID 保存在 `var/run/`。

演示样例报文保存在 `fixtures/`：

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
- 本地 Demo 不要求 Redis、Nacos、Kafka、ES 或容器环境。
- ESB 请求/响应队列由 `jsnx-edsp-esb-adapter` 内存队列模拟。
- Kafka 异步日志由非阻塞 HTTP 事件投递模拟；监控服务不可用时只允许丢日志，不阻断业务请求。
- Nacos 配置入口保留但默认关闭；Gateway 使用明确 URI，不引入 Spring Cloud LoadBalancer。
- 发布计划持久化到 MySQL，数据源应用按接口加载本地 PlanSnapshot，并通过 AtomicReference 原子切换。
- 公共能力服务仅用于按需复杂处理，普通字段映射、签名、加解密和响应标准化在目标数据源应用内执行。
- Elasticsearch、GoldenDB、Doris 当前不引入。
- 所有服务以独立 JVM 运行，不依赖容器。
