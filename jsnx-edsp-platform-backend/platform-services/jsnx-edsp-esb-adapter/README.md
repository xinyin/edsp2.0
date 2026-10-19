# jsnx-edsp-esb-adapter

ESB 报文适配服务，端口 `8084`。

## 接入模式

`edsp.esb.mode` 在 Nacos 的 `jsnx-edsp-esb-adapter.yaml` 中设置为 `MOCK`（本地模拟，默认）或 `IBM_MQ`（银行 IBM MQ）。仅在启动时选择模式，修改后重启 Adapter；模式值或 MQ 必填项错误会导致启动失败，不会自动回退到模拟模式。

### 生产工程依赖对照

旧工程截图中的 `com.ibm.mq:mq`、`common-services`、`headers` 是 `com.ibm.mq` 坐标下的旧工程依赖，`${ibm-mq.version}=1.0` 是该工程定义的 Maven 属性，不能据此推断 IBM MQ 官方客户端版本。当前平台基于 Spring Boot 3 / JDK 17 的 `jakarta.jms`，使用 `com.ibm.mq:com.ibm.mq.jakarta.client:9.4.0.0` 和 Spring JMS。不要直接把旧版 `javax.jms` 依赖与当前客户端混装。若行内仓库只提供内部封装包，应先核对它的实际 POM、`jakarta.jms` 兼容性和银行认证版本，再调整依赖；队列名和连接参数不能从这三张截图中确定。

### Nacos 配置

Data ID 为 `jsnx-edsp-esb-adapter.yaml`，Group、Namespace 分别由部署时的 `NACOS_GROUP`、`NACOS_NAMESPACE` 确定。仓库模板位于 `jsnx-edsp-deployment/config/nacos/data/jsnx-edsp-esb-adapter.yaml`。真实接入至少需要在对应环境的 Nacos 中确认以下配置；以下是字段名，不是生产连接值：

| 配置项 | 含义 |
| --- | --- |
| `edsp.esb.mode` | `MOCK` 或 `IBM_MQ`，切换后重启 Adapter |
| `edsp.esb.ibm-mq.g1.*`、`g2.*` | 两套同时消费的独立 MQ 连接，以下字段两边都必填 |
| `host`、`port` | MQ 服务端地址和监听端口 |
| `queue-manager`、`channel` | 队列管理器和客户端通道；g1/g2 的队列管理器必须不同 |
| `request-queue`、`response-queue` | 平台消费的请求队列、发送结果的响应队列 |
| `concurrency` | 每套连接的消费线程数范围，例如 `1-4` |
| `username`、`password` | MQ 连接身份；通过部署环境变量/密钥管理注入，不提交明文 |
| `tls-cipher-suite`、`tls-peer-name` | TLS 套件及服务端证书主体约束（启用 TLS 时） |
| `edsp.esb.ibm-mq.caller-keys-json` | `requester_id` 到平台调用方 API Key 的映射；密钥值从安全配置注入 |
| `edsp.esb.environment` | Gateway 授权环境，投产时明确设为 `PROD` |

旧系统配置已经确认是双活，且从旧系统 `sq` 消费、向 `rq` 回写。因此两套 `request-queue` 均对应旧系统的 `sq`，两套 `response-queue` 均对应 `rq`；相同队列名位于不同队列管理器，互不共用连接。每个监听容器用自己的 MQ 连接和事务会话完成消费与响应发送。单边 MQ 故障时另一边可继续处理本侧流量，但不能自动接管故障侧队列积压；ESB 上游应负责入口分流或故障切换。

变更已有 Nacos Data ID 前先比对线上内容；同步脚本默认不会覆盖已存在的配置。正式切换前需由 ESB 团队确认两侧连接身份、TLS 证书、队列权限及报文样例，并在测试环境完成往返、单边故障和重投验收。缺少这些值时保持 `MOCK`，不能把占位值当作生产配置发布。截图中还有数据库及 Redis 凭据，均不属于本 Adapter 配置，不能复制进 Nacos 的 MQ Data ID。

- `MOCK`：`POST /esb/queue/request` 把 XML 放入进程内队列，供本地演示与 Postman 联调。
- `IBM_MQ`：监听固定请求队列，按现有 XML 协议调用 Gateway，把 XML 结果写入固定响应队列。普通 HTTP 模拟入口返回 404；受管理端密钥保护的 `diagnostic=true` 仍可用于诊断，但不经过 MQ。

真实模式需完整配置 `edsp.esb.ibm-mq.g1` 和 `g2`；缺少任一侧或两侧队列管理器相同则启动失败。每侧的请求和响应队列须位于该侧的同一队列管理器，使用同一个事务完成响应发送和请求确认；响应发送失败时事务回滚，由 MQ 重投。消费业务失败则写标准错误 XML，不无限重试已处理的业务错误。IBM MQ 连接异常由监听容器每 5 秒尝试恢复。请求消息必须为 XML `TextMessage`；响应持久化发送到对应侧的响应队列，`JMSCorrelationID` 沿用入站 correlation ID，缺失时使用请求 `JMSMessageID`。不接受入站 `JMSReplyTo` 指定任意响应目标。

MQ 事务不覆盖 Gateway 或供应商的外部调用。响应入队失败后的重投可能再次调用供应商；上线前必须确认 `service_sn` 在业务链路的幂等策略，尤其是计费接口。共享请求队列中的 `requester_id` 来自 XML，不能单独作为调用方身份凭据；行内 ESB 应限制队列写入权限并提供可信调用方身份校验。未完成该安全约定及银行 MQ 联调前，不应切换生产流量。

MQ 连接认证分别使用 `ESB_MQ_G1_USERNAME` / `ESB_MQ_G1_PASSWORD` 和 `ESB_MQ_G2_USERNAME` / `ESB_MQ_G2_PASSWORD`。启用 TLS 时分别设置 `ESB_MQ_G1_TLS_CIPHER_SUITE`、`ESB_MQ_G2_TLS_CIPHER_SUITE`，按银行证书要求通过 JVM `javax.net.ssl.trustStore` 等参数配置可信证书。调用方密钥不从 XML 读取：`Service_Header/requester_id` 映射到 `ESB_MQ_CALLER_KEYS_JSON` 中的 API Key，例如 `{"CALLER-1":"<secret>"}`。在部署系统的密钥管理中注入这些环境变量，不要把明文写入 Git 或 Nacos。`KAFKA_ENABLED` 在 IBM MQ 模式下保持 `false`，除非明确需要额外的 Kafka 测试通道。

修改仓库配置模板后，按部署手册执行 `jsnx-edsp-deployment/bin/sync-config-to-nacos.sh` 同步到 Nacos，检查已存在的同名 Data ID 是否需要 `FORCE=1` 覆盖，然后重启 Adapter。投产前须使用银行测试队列验收通道/TLS、请求和响应队列权限、相关 ID、回滚重投、并发及下游超时；本仓库单元测试不能替代真实 MQ 联调。

## 职责

- 接收 ESB 队列或 XML 报文。
- 按 `Service / Service_Header / Service_Body` 契约解析 ESB XML，将业务体转换为平台内部 JSON。
- 透传全局流水号、机构号、调用系统号和源系统号。
- 将平台标准 JSON 响应封装为同一契约的 XML，并写回 ESB 响应链路。
- Kafka 请求和响应主题继续使用结构化 JSON，不承载 ESB XML 协议转换职责。

## 接口

```text
POST /esb/forward
Content-Type: application/xml
Accept: application/xml
```

正常请求返回 `application/xml`。联调页面使用 `?diagnostic=true` 获取 JSON 诊断对象，其中同时包含 `standardResponse` 和最终 `xmlResponse`。

Gateway 授权环境由 `edsp.esb.environment` 配置，默认 `TEST`；生产环境应在 Nacos 中明确设置为 `PROD`，不从业务请求报文接受环境覆盖。

生产请求至少包含：

```xml
<Service>
  <Service_Header>
    <service_sn>全局流水号</service_sn>
    <service_id>平台标准服务编码</service_id>
    <requester_id>调用系统编码</requester_id>
    <service_time>yyyyMMddHHmmss</service_time>
    <version_id>01</version_id>
  </Service_Header>
  <Service_Body><request>业务字段</request></Service_Body>
</Service>
```

ESB 适配只负责协议转换，不负责业务字段映射、供应商加解密或统一返回码；业务执行由目标数据应用在本地完成。

## 构建

```bash
mvn test
mvn package -DskipTests
```
