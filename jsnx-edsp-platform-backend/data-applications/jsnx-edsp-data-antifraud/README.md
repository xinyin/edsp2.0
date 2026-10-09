# 百融反欺诈数据源应用

独立 JVM、独立端口和独立发布单元。通过统一内部入口 `POST /internal/adapter/published` 接收
Gateway 已校验的 `X-Api-Code` 与 `X-Route-Version`，应用内通过 Platform SDK 加载对应已发布计划，
完成请求转换、安全处理、真实条件查询、响应标准化与异步节点日志。默认端口为 `8183`。

发布后 Admin 只通知本应用刷新对应 PlanSnapshot，不需要重启，也不会影响企业工商和征信应用。
