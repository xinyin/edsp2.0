# 外部数据平台 API 调用日志规范

## 1. 日志分层

- 调用摘要：每次调用一条记录，用于检索、统计、计费和告警。异步事件可经 Kafka 传递，最终统一落 MySQL。
- 节点明细：按 Trace ID 保存 Gateway、校验、请求转换、安全处理、数据源应用、供应商、响应标准化和计费节点。
- 原始报文：按脱敏、压缩和大小限制后保存至 MySQL 报文表；超大报文仅保留摘要和必要审计字段。

## 2. 必备上下文字段

所有模块使用相同 MDC 字段并透传：`traceId`、`spanId`、`parentSpanId`、`interfaceCode`、`clientCode`、`institutionCode`、`providerCode`、`dataSourceCode`、`publishedPlanVersion`、`environment`。

调用摘要必须包含：调用时间、请求状态、结果状态、统一返回码、端到端耗时、Gateway 耗时、上游耗时、调用系统、接口编码、计费标识和执行实例。

节点明细必须包含：节点编码、节点类型、节点状态、开始时间、耗时、错误分类、输入摘要、输出摘要和顺序号。

## 3. 状态与错误分类

- 请求状态：`SUCCESS`、`FAILED`、`TIMEOUT`、`REJECTED`。
- 结果状态：`HIT`、`MISS`、`ERROR`。
- 节点状态：`SUCCESS`、`ERROR`、`SKIPPED`。
- 错误分类：认证失败、授权失败、限流、路由失败、Schema 校验失败、映射失败、加解密失败、上游超时、上游业务错误、响应标准化失败和平台内部错误。

平台异常必须使用统一返回码，禁止只记录异常文本。异常堆栈写错误日志，调用摘要只保存错误类型和错误摘要。

## 4. 安全要求

- 身份证、手机号、银行卡号、姓名、地址和企业敏感信息按字段规则脱敏。
- 密钥、Token、Cookie、Authorization 和签名原文禁止落库。
- 请求和响应展示受日志查看权限控制，并记录查看审计日志。
- 报文保存前执行大小限制、JSON 合法性检查和敏感字段清洗。

## 5. 保留与性能

- MySQL：保存调用摘要、链路节点、计费关联、报文摘要和聚合查询所需数据；按时间分区或分表管理历史明细。
- Kafka：日志异步采集缓冲，消费者批量写入 MySQL；Kafka 不作为最终事实数据源。

Demo 使用 MySQL 的 `usage_record` 和 `api_call_node_log` 模拟摘要与节点明细分层。生产环境可通过 Kafka 异步批量落 MySQL，避免日志写入影响 API 主调用链。
