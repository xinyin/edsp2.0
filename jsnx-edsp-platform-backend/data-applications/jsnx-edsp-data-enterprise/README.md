# jsnx-edsp-data-enterprise

企业工商数据独立处理应用，默认端口 `8181`，对应数据源编码 `ENTERPRISE`。

## 业务范围

- 企业基本信息查询。
- 企业工商照面和企业风险类接口的独立适配。
- 仅接受执行计划路由到 `ENTERPRISE` 的请求。

## 内部接口

- `GET /internal/adapter/info`：应用身份和健康信息。
- `POST /internal/adapter/published`：统一生产执行入口；Gateway 通过 `X-Api-Code`、`X-Route-Version` 指定已发布计划，应用完成字段映射、安全处理、供应商调用和响应标准化。
- `POST /internal/adapter/plans/{interfaceCode}/refresh`：发布后仅刷新本应用对应接口的本地 PlanSnapshot。

该应用拥有独立 JVM、端口、日志和启停生命周期，不依赖集中式 Runtime。企业数据源故障不会导致征信或反欺诈数据应用停止。

```bash
mvn spring-boot:run
```
