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
| AES 字段加密联调 | `POST /vendor/crypto/aes-field` |

平台只依赖开放目录和服务调用接口，不直接读取本应用的 MySQL。

## AES 字段加密联调

Vendor 提供独立的 `POST /vendor/crypto/aes-field` 模拟接口，不需要数据库样例数据。请求为 JSON，`customer_id` 必须是 AES-256-GCM 密文；成功时返回 `result.code=0000`、`data.customer_id`（解密后的测试值）和 `data.encryptionVerified=true`。明文、错误密钥或错误 AAD 返回 HTTP 400、业务码 `4003`。

在平台数据资源接入中登记此接口，请求样例为 `{"customer_id":"CUST-001"}`，响应样例为 `{"result":{"code":"0000","message":"加密字段校验通过"},"data":{"customer_id":"CUST-001","encryptionVerified":true}}`。资源接入测试只测试上游原始请求，不会自动加密；在标准服务开发的安全处理中启用 `AES_256_GCM`、请求字段填 `customer_id`，加密密钥引用填 `env://VENDOR_CRYPTO_TEST_KEY`，再执行草稿链路测试。Admin 和实际处理该资源的独立应用都须设置同名环境变量并重启；Vendor 通过 `VENDOR_CRYPTO_TEST_KEY` 读取相同密钥。

模拟环境默认测试密钥（32 字节 Base64）为 `MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=`。平台加密输出格式为 `v1.<Base64URL IV>.<Base64URL 密文及认证标签>`，AAD 固定为字段路径 `customer_id`。该密钥仅供本地联调，不用于真实供应商或生产环境。Vendor 独立启动后才能访问本接口。

使用默认测试密钥时，可直接发送以下固定密文验证接口，预期返回 `data.customer_id=CUST-001`。固定 IV 只用于这条测试向量；平台实际请求每次使用随机 IV。

```bash
curl -X POST http://127.0.0.1:8090/vendor/crypto/aes-field \
  -H 'Content-Type: application/json' \
  -d '{"customer_id":"v1.AAAAAAAAAAAAAAAA.jSbyVT8zb2kr4K5XAuNiRC95v3bJpaJv"}'
```

## 当前业务样例

- 企业工商基本信息查询：按统一社会信用代码查询。
- 征信报告查询：按业务查询关键字返回征信报告样例。

```bash
mvn clean package -DskipTests
```
