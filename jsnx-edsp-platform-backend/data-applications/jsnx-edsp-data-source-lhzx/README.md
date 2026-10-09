# jsnx-edsp-data-source-lhzx

`LHZX` 数据源独立处理应用，默认端口 `8191`。该应用是独立 JVM、独立部署和独立故障边界，
不与企业工商、征信或百融反欺诈应用共享运行进程。

## 运行职责

- `POST /internal/adapter/published`：接收 Gateway 动态路由后的生产请求。
- 使用 `X-Api-Code` 和 `X-Route-Version` 加载对应的已发布 PlanSnapshot。
- 在本应用内完成字段转换、安全处理、上游调用、返回码映射和标准响应。
- `POST /internal/adapter/execute`：管理面的草稿联调入口。
- `POST /internal/adapter/plans/{interfaceCode}/refresh`：发布后热切换指定接口计划。

本地运行地址为 `http://127.0.0.1:8191`。生产环境的 `data_source.runtime_url` 必须配置为
该应用集群的行内 F5/VIP 地址，不能填写单实例地址。
