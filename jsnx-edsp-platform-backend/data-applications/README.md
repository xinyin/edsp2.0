# 独立数据应用

每个子目录都是按数据源隔离部署的 Spring Boot 应用，拥有独立端口、JAR、PID、日志和发布周期。

| 应用 | 数据源 | 端口 |
| --- | --- | ---: |
| `jsnx-edsp-data-enterprise` | 企业工商 `ENTERPRISE` | 8181 |
| `jsnx-edsp-data-credit` | 征信报告 `CREDIT` | 8182 |
| `jsnx-edsp-data-antifraud` | 百融反欺诈 `ANTIFRAUD` | 8183 |
| `jsnx-edsp-data-source-lhzx` | 联合征信 `LHZX` | 8191 |

生产调用链：

```text
Gateway → 数据源独立应用
            ├→ 本应用 LocalPlanRuntime：请求转换、安全处理和响应标准化
            └→ 上游供应商：实际业务调用
```

数据源应用只依赖稳定的 `jsnx-edsp-contracts` 和 `jsnx-edsp-client-sdk`。一次请求固定持有一个不可变
PlanSnapshot，在途 V3 请求不会被并发发布的 V4 影响。新增数据应用时复制应用骨架，登记应用名、
数据源编码、端口及 `runtime_url`，然后在 Admin 中配置并发布执行计划；不再修改 Gateway 路由配置。
