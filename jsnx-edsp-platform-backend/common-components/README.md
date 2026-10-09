# 公共组件

本目录包含稳定接口组件和平台服务内部实现，不代表所有数据源应用都需要引入。

## 数据源应用依赖

- `jsnx-edsp-contracts`：数据应用与公共能力服务之间的稳定请求响应契约。
- `jsnx-edsp-client-sdk`：公共能力服务客户端和上游 HTTP 调用封装。

## capability-runtime 内部实现

- `jsnx-edsp-plan-core`：执行计划解析、版本和缓存。
- `jsnx-edsp-transform-core`：请求与响应字段映射。
- `jsnx-edsp-crypto-core`：加解密能力。
- `jsnx-edsp-signature-core`：签名与验签。
- `jsnx-edsp-response-core`：统一响应模型。

## 平台服务内部实现

- `jsnx-edsp-platform-core`：Admin 领域模型、配置、审批和发布。
- `jsnx-edsp-log-core`：Gateway 和观测侧的 Trace、脱敏及异步日志。

旧架构的 `jsnx-edsp-adapter-core`、重复的 `jsnx-edsp-http-client` 和没有实际实现的 `jsnx-edsp-bootstrap` 已从 Maven 聚合及应用依赖中移除。
