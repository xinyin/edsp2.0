# jsnx-edsp-data-credit

征信报告数据独立处理应用，默认端口 `8182`，对应数据源编码 `CREDIT`。

## 业务范围

- 个人征信报告查询。
- 征信类供应商协议和接口的独立适配。
- 仅接受执行计划路由到 `CREDIT` 的请求。

## 内部接口

- `GET /internal/adapter/info`：应用身份和健康信息。
- `POST /internal/adapter/published`：统一生产执行入口；Gateway 通过 `X-Api-Code`、`X-Route-Version` 指定已发布计划，应用完成字段映射、安全处理、供应商调用和响应标准化。
- `POST /internal/adapter/plans/{interfaceCode}/refresh`：发布后仅刷新本应用对应接口的本地 PlanSnapshot。

该应用可独立扩容、发布、停机和恢复，不依赖集中式 Runtime，也不影响企业工商或反欺诈数据应用。

```bash
mvn spring-boot:run
```
