# EDSP 2.0 Demo 演示手册

本手册用于演示“上游供应商独立管理 + 外部数据平台配置化适配 + 不停机发布 + 全链路追踪”。平台后端、平台前端、供应商后端和供应商前端是四个独立工程。

## 一、环境与端口

| 工程/服务 | 端口 | 作用 |
| --- | ---: | --- |
| 平台前端 | 5173 | 外部数据平台运营工作台 |
| `jsnx-edsp-data-gateway` | 8080 | 认证、授权、限流、路由、Trace、异步日志 |
| `jsnx-edsp-data-admin` | 8081 | 服务商、数据源、API、映射、审批、授权、计费 |
| `jsnx-edsp-data-observability` | 8083 | 调用日志、链路追踪、监控运维 |
| `jsnx-edsp-esb-adapter` | 8084 | ESB 报文接入和协议适配 |
| `jsnx-edsp-data-enterprise` | 8181 | 企业工商独立数据应用 |
| `jsnx-edsp-data-credit` | 8182 | 征信独立数据应用 |
| `jsnx-edsp-data-antifraud` | 8183 | 百融反欺诈独立数据应用 |
| `jsnx-edsp-data-source-lhzx` | 8191 | 联合征信 LHZX 独立数据应用 |
| `jsnx-edsp-capability-runtime` | 8091 | 可选复杂能力服务，非必经节点 |
| 供应商后端 | 8090 | 上游供应商 API 模拟 |
| 供应商前端 | 5190 | 上游数据源和 API 管理工作台 |

当前 Demo 默认使用本机 MySQL：

```text
平台数据库：edsp_demo
供应商数据库：vendor_demo
地址：127.0.0.1:3306
用户名：root
密码：空
```

可以通过 `DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD`、`DB_URL` 覆盖默认值。

## 二、启动和停止

先确认本机 MySQL 已启动。外部数据平台独立启动：

```bash
cd /Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment
./bin/start-demo.sh
```

该脚本只会：

1. 初始化 `edsp_demo` 数据库；
2. 构建并启动 Admin、Observability、可选能力服务、独立数据应用、ESB Adapter、Gateway；
3. 启动平台前端 `5173`。

停止外部数据平台：

```bash
./bin/stop-demo.sh
```

供应商模拟平台拥有独立生命周期，需要联调时单独执行：

```bash
./bin/start-vendor.sh
./bin/stop-vendor.sh
```

任一平台启动或停止都不会联动另一平台。`run-full-demo.sh` 和 `verify-demo.sh` 仅在两套平台均已启动后用于跨系统联调，不负责启动或停止服务。

日志位置：

```text
/Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment/var/logs/
```

## 三、平台登录

打开 <http://127.0.0.1:5173/>：

```text
用户名：admin
密码：admin123
```

## 四、默认样例数据

### 1. 供应商和数据源

```text
服务商编码：QXB
服务商名称：企信数据服务商
数据源编码：ENTERPRISE
数据源名称：企业工商数据源
独立应用：jsnx-edsp-data-enterprise
服务端口：8181
```

### 2. 企业工商 API

```text
接口编码：ENTERPRISE_BASIC
接口名称：企业基本信息查询
平台路径：/api/v1/enterprise/basic
上游路径：/vendor/api/enterprise-basic
请求字段：creditCode、queryReason
供应商字段：credit_code、query_reason
```

### 3. 已初始化的企业记录

| 查询字段 | 企业名称 | 结果 |
| --- | --- | --- |
| `91320583MA1MOCK01` | 昆山模拟科技有限公司 | 命中，返回企业信息 |
| `91110000802100433B` | 百度在线网络技术（北京）有限公司 | 命中，返回企业信息 |
| `91440300708461136T` | 腾讯科技（深圳）有限公司 | 命中，返回企业信息 |
| `91320583NOTFOUND01` | 不存在 | 返回供应商码 `1001`，平台码 `EXT-NO-DATA` |

## 五、一键演示完整调用链

```bash
cd /Users/tommy/Documents/外部数据/demo/jsnx-edsp-deployment
./bin/run-full-demo.sh
```

脚本按以下顺序执行：

```text
1. 读取供应商开放目录（`GET /open/catalog`）
2. 直接调用供应商接口，验证真实企业记录命中
3. 直接调用供应商接口，验证不存在企业返回未查得
4. 发送 XML 到模拟 ESB 请求队列
5. 校验 ESB Adapter 是否完成请求 XML -> JSON 协议转换，并直接返回平台统一 JSON 响应
6. 校验 Gateway 授权和一级路由
7. 校验平台是否完成 creditCode -> credit_code 转换
8. 校验请求是否只进入 jsnx-edsp-data-enterprise:8181
9. 校验数据应用是否调用供应商 8090
10. 校验 data.qymc、data.fddbr、data.xingbie 的响应标准化
11. 校验百融反欺诈独立应用 jsnx-edsp-data-antifraud:8183
12. 校验个人征信独立应用 jsnx-edsp-data-credit:8182
13. 校验联合征信独立应用 jsnx-edsp-data-source-lhzx:8191
13. 校验 Gateway 未授权拒绝、调用日志、Trace、监控摘要和 ESB 队列状态
```

真实演示入口是 ESB XML 队列，调用示例：

```bash
curl -X POST 'http://127.0.0.1:8084/esb/queue/request?servicePath=/api/v1/enterprise/basic' \
  -H 'X-App-Id: CREDIT_RISK' \
  -H 'X-Api-Key: demo-secret-key' \
  -H 'X-Trace-Id: DEMO-ENTERPRISE-HIT-001' \
  -H 'Content-Type: application/xml' \
  --data-binary '@fixtures/esb-enterprise-hit.xml'
```

预期响应头为 `Content-Type: application/xml`，结果重点：

```xml
<Service>
  <Service_Header>
    <service_response>
      <code>000000</code>
      <status>COMPLETE</status>
    </service_response>
    <service_sn>DEMO-ENTERPRISE-HIT-001</service_sn>
  </Service_Header>
  <Service_Body>
    <request>...</request>
    <response>
      <response_code>100</response_code>
      <data>...</data>
      <resp_code>EXT-SUCCESS</resp_code>
      <status>100</status>
    </response>
  </Service_Body>
</Service>
```

Adapter 到 Gateway、数据应用内部以及 Kafka 日志事件仍使用结构化 JSON；XML 只位于 ESB 协议边界。

不存在企业的调用：

```bash
curl -X POST 'http://127.0.0.1:8084/esb/queue/request?servicePath=/api/v1/enterprise/basic' \
  -H 'X-App-Id: CREDIT_RISK' \
  -H 'X-Api-Key: demo-secret-key' \
  -H 'X-Trace-Id: DEMO-ENTERPRISE-MISS-001' \
  -H 'Content-Type: application/xml' \
  --data-binary '@fixtures/esb-enterprise-miss.xml'
```

## 六、页面演示流程

### 场景 A：上游供应商新增 API

1. 打开 <http://127.0.0.1:5190/>；
2. 进入“数据源管理”；
3. 在 `ENTERPRISE` 数据源下新增 API；
4. 填写接口编码、服务路径、HTTP 方法、请求样例和响应报文；
5. 保存后确认供应商服务路径可直接调用；
6. 打开平台 <http://127.0.0.1:5173/>；
7. 进入“供应商能力接入”，刷新开放目录；
8. 进入对应数据源，创建平台 API 草稿；
9. 配置请求映射、响应映射、统一返回码和安全策略；
10. 点击“校验”和“联调测试”。

### 场景 B：平台字段标准化

以企业工商 API 为例：

```text
下游字段                 供应商字段
creditCode       ->     credit_code
queryReason      ->     query_reason

供应商字段                 行内标准字段
data.qymc        ->     data.enterpriseName
data.credit_code ->     data.creditCode
data.fddbr       ->     data.legalPerson
data.xingbie     ->     data.sex
```

性别枚举配置：

```text
1 -> MALE
2 -> FEMALE
```

### 场景 B.1：数据源默认配置与接口覆盖

1. 接入数据源时配置供应商基础地址和数据源默认超时；
2. 新增 API 时选择所属数据源；
3. 普通接口选择“继承数据源”，只填写供应商相对路径；
4. 特殊接口选择“单接口覆盖”，填写独立完整地址；
5. 超时策略可单独选择继承或覆盖，不受地址策略限制；
6. 保存后基本信息页显示最终生效地址、最终超时和配置来源；
7. 本期调度模式固定为 `DIRECT`，执行计划已预留 Router 字段。

### 场景 C：审批发布与不停机生效

1. 在 API 页面修改请求映射或上游地址；
2. 保存草稿；
3. 执行接口校验；
4. 执行上游连通性测试；
5. 执行“下游→平台→上游”端到端联调；
6. 提交审批；
7. 在审批中心执行通过；
8. 发布生成新的执行计划版本；
9. 目标数据源应用拉取 MySQL 中的发布快照，并在本 JVM 内原子切换计划；
10. 不重启 `jsnx-edsp-data-enterprise`，再次调用接口验证新规则。

生效规则：

```text
新请求：立即使用新计划 V4
在途请求：继续使用旧计划 V3
应用重启：通过 Admin 从 MySQL 发布快照恢复，不要求重新发布
```

## 七、调用日志和监控验证

查询指定 Trace：

```bash
curl 'http://127.0.0.1:8080/api/call-logs/DEMO-ENTERPRISE-HIT-001'
```

查询企业接口调用日志：

```bash
curl 'http://127.0.0.1:8080/api/call-logs?interfaceCode=ENTERPRISE_BASIC'
```

查询监控摘要：

```bash
curl 'http://127.0.0.1:8080/api/monitor/summary'
```

重点观察：

- Gateway 接入节点状态；
- 请求转换节点入参和出参；
- 安全处理节点状态；
- 上游调用地址、HTTP 状态和供应商返回码；
- 响应标准化结果；
- 模拟 Kafka 的非阻塞异步事件是否进入监控服务；
- `global_serial_no`/Trace ID 是否贯穿全链路。

## 八、常见问题

### MySQL 连接失败

确认 `mysql -h 127.0.0.1 -uroot` 可以连接，再执行启动脚本。需要密码时：

```bash
DB_PASSWORD='你的密码' ./bin/start-demo.sh
```

### 端口已经被占用

查看端口占用：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

脚本发现端口已有服务时会跳过可识别的服务；如果 8080 是其他程序占用，需要先处理端口冲突。

### Kafka/Nacos 未启动

当前 Demo 默认关闭 Nacos 注册发现，ESB 队列使用内存模拟，日志事件使用非阻塞 HTTP 模拟 Kafka，因此两者均不是本地演示前置条件。Kafka 或监控服务不可用时不应阻断 Gateway 主请求；如果需要切换真实中间件，可设置：

```bash
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092 ./bin/start-demo.sh
```

Nacos 已提供独立本地部署脚本，运行 `./bin/start-nacos.sh` 启动。开启应用注册发现与配置管理还需配置 Nacos 鉴权凭证，并重启需要接入的业务应用；完整步骤见 [`docs/Nacos本地部署.md`](docs/Nacos本地部署.md)。仅启动 Nacos 不改变已经运行的业务应用。
