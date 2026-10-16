# jsnx-edsp-data-gateway

外部数据平台统一入口 Gateway，端口 `8080`。

## 职责

- 统一接收行内系统调用请求。
- 执行调用方认证、API 授权、请求头校验、限流和路由转发。
- 生成并透传 `X-Trace-Id`，记录 Gateway 节点日志。
- 异步日志传输不可用时允许丢日志，但不能阻断或拖垮业务请求。
- Gateway 只负责入口治理和一级路由，不执行字段映射、签名、加解密或供应商协议适配。

## 路由

Gateway 不再维护企业、征信、反欺诈等固定业务前缀。Admin 发布接口时，把线上路由快照写入
`published_route`；Gateway 拉取完整快照后使用 `AtomicReference` 一次性替换本地只读路由表。
每次业务调用必须同时匹配 `X-Api-Code`、HTTP Method 和 URL Path，再转发到资源提供商登记的
`runtime_url + /internal/adapter/published`。

```text
apiCode + Method + Path
  → Gateway 本地已发布路由快照
  → data_provider.runtime_url（生产填写行内 VIP）
  → 资源提供商处理应用统一执行入口
```

新增数据源或接口不修改 Gateway YAML、不重新构建 Gateway、不重启 Gateway。发布事务提交后由
Admin 主动通知所有 Gateway 实例刷新；每个实例还会按 5 秒周期全量拉取，通知丢失或 Admin
短暂不可用时继续使用上一份有效快照。

路由表和草稿严格隔离：`api_interface` 保存当前编辑态，`published_route` 只保存线上版本；只有
发布成功才会原子更新线上路由，下线则删除线上路由。

## 关键组件

- `PublishedRouteRegistry`：加载、校验并原子切换已发布路由快照，异常时保留 last-good 快照。
- `DynamicDataApplicationRoutingFilter`：执行 `apiCode + Method + Path` 强一致校验并选择资源提供商处理应用 VIP。
- `ExternalApiAuthFilter`：校验调用凭证，并核对调用系统、API 授权、有效期和日配额。
- `RouteRefreshController`：提供发布后主动刷新和实例路由状态检查。
- `TraceGlobalFilter`：Trace ID、调用耗时和入口日志。
- `GatewayLogGlobalFilter`：采集业务 API 的入口、路由、响应状态和异常日志。
- `GatewayLogPublisher`：通过 `log-core` 有界队列异步投递；本地 Demo 使用 HTTP 模拟 Kafka，生产可切换 Kafka。队列满或传输故障只丢弃并计数。
- Spring Cloud Gateway、Nacos Config/Discovery、Sentinel Gateway。

生产环境必须通过环境变量或 Nacos 覆盖调用凭证、Admin 地址和 Gateway 实例列表；内部管理接口
只允许管理网段访问。Gateway 不使用 Spring Cloud LoadBalancer，数据应用多实例负载由行内
F5/VIP 承担。

## 构建

```bash
mvn test
mvn package -DskipTests
```
