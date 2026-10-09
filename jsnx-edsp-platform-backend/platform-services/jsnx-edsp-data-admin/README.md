# jsnx-edsp-data-admin

外部数据平台配置管理与运营服务，端口 `8081`。

## 职责

- 管理数据服务商、服务商账号、数据源和 API 定义。
- 提供请求映射、响应标准化、统一返回码和安全策略配置。
- 提供接口校验、联调测试、发布审批、服务授权和计量计费配置。
- 发布时生成 `PublishedPlan`，并将执行计划快照持久化到 MySQL。

## 主要接口

| 能力 | 路径 |
| --- | --- |
| 登录与看板 | `/api/auth/login`、`/api/dashboard` |
| 服务商管理 | `/api/providers`、`/api/providers/{id}/accounts` |
| 数据源/API | `/api/data-sources`、`/api/interfaces` |
| 字段映射 | `/api/interfaces/{id}/mappings` |
| 安全策略 | `/api/interfaces/{id}/security-policy` |
| 联调测试 | `/api/interfaces/{id}/test` |
| 审批发布 | `/api/interfaces/{id}/approval-requests`、`/api/approvals/{id}/decision` |
| 授权计费 | `/api/clients`、`/api/authorizations`、`/api/billing/rules` |

## 发布计划

草稿保存在配置表中，生产调用只使用审批通过的计划。发布快照存储在 `release_plan_snapshot`，服务重启后自动恢复已发布版本。

## 配置与构建

- MySQL：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`
- Nacos：`NACOS_ENABLED`、`NACOS_SERVER_ADDR`
- Redis/Kafka：通过 `REDIS_*`、`KAFKA_BOOTSTRAP_SERVERS` 配置

```bash
mvn test
mvn package -DskipTests
```
