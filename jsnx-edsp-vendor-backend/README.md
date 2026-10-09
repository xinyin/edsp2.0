# EDSP 上游供应商模拟后端

独立 Spring Boot 工程，使用 `vendor_demo` MySQL 数据库，服务端口 `8090`。

## 职责

- 维护供应商数据源、数据源下的 API 和企业数据记录。
- 对外提供实时开放目录，平台无需共享供应商数据库即可发现能力。
- 根据真实请求参数查询供应商数据库，命中返回真实数据，未命中返回无数据。
- 支持动态新增、修改、停用 API，用于验证平台不停机接入能力。

## 主要接口

| 能力 | 路径 |
| --- | --- |
| 数据源管理 | `/admin/data-sources` |
| API 管理 | `/admin/apis` |
| 企业记录 | `/admin/enterprise-records` |
| 开放目录 | `GET /open/catalog` |
| 模拟服务调用 | `POST /vendor/api/**` |

平台只依赖开放目录和服务调用接口，不直接读取本应用的 MySQL。

## 当前业务样例

- 企业工商基本信息查询：按统一社会信用代码查询。
- 征信报告查询：按业务查询关键字返回征信报告样例。

```bash
mvn clean package -DskipTests
```
