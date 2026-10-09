# jsnx-edsp-capability-runtime

EDSP 2.0 可选复杂能力服务，默认端口 `8091`。它不是正常同步调用链的必经节点；仅当某个接口需要跨数据源复用的重型、复杂或集中治理能力时按需调用。

## 可选运行能力

- `POST /internal/capability/prepare`：按已发布计划完成请求字段映射，返回上游地址、超时和上游报文。
- `POST /internal/capability/complete`：完成响应字段标准化、供应商返回码转换和计费标识计算。
- `POST /internal/capability/refresh?interfaceCode=...`：清理指定接口的本地计划缓存并从 Admin 恢复最新已发布版本。
- `GET /internal/capability/health`：健康检查。

普通字段映射、统一响应、安全处理和 PlanSnapshot 原子切换已经在各独立数据源应用本地完成。该服务保留为扩展口，不应成为所有数据应用的单点依赖。
