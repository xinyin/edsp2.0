# jsnx-edsp-data-enterprise

企信资源提供商独立处理应用，默认端口 `8181`，对应资源提供商编码 `QXB`。

## 业务范围

- 承载企信资源提供商下企业工商、征信、反欺诈等多个数据源。
- 处理提供商级认证、协议适配以及特殊扩展逻辑。
- 仅接受 `providerCode=QXB` 的内部调用，不限制其下的数据源编码。

## 内部接口

- `GET /internal/adapter/info`：应用身份和健康信息。
- `POST /internal/adapter/published`：统一生产执行入口；Gateway 通过 `X-Api-Code`、`X-Route-Version` 指定已发布计划，应用完成字段映射、安全处理、供应商调用和响应标准化。
- `POST /internal/adapter/plans/{interfaceCode}/refresh`：发布后仅刷新本应用对应接口的本地 PlanSnapshot。

该应用拥有独立 JVM、端口、日志和启停生命周期，不依赖集中式 Runtime。一个资源提供商只配置一个处理应用路由，其下所有数据源与 API 统一经过该应用。

```bash
mvn spring-boot:run
```
