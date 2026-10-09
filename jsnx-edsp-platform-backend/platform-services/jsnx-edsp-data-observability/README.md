# jsnx-edsp-data-observability

调用日志与监控运维服务，端口 `8083`。

## 职责

- 查询成功、失败和慢调用日志。
- 按 Trace ID 展示完整调用链和各节点状态。
- 提供 Gateway、数据源应用、供应商的耗时统计。
- 提供告警、P95、异常率和资源健康指标。

## 接口

| 能力 | 路径 |
| --- | --- |
| 用量明细 | `GET /api/usage-records` |
| 调用日志 | `GET /api/call-logs` |
| 调用链路 | `GET /api/call-logs/{traceId}` |
| 监控汇总 | `GET /api/monitor/summary` |
| 监控节点 | `GET /api/monitor/points` |
| 告警列表 | `GET /api/monitor/alerts` |

日志建议由 Gateway 和数据应用异步写入 Kafka，再由日志处理应用落地原始明细。Kafka 故障时不得影响主调用链。

## 构建

```bash
mvn test
mvn package -DskipTests
```
