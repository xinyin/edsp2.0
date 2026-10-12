# jsnx-edsp-esb-adapter

ESB 报文适配服务，端口 `8084`。

## 职责

- 接收 ESB 队列或 XML 报文。
- 按 `Service / Service_Header / Service_Body` 契约解析 ESB XML，将业务体转换为平台内部 JSON。
- 透传全局流水号、机构号、调用系统号和源系统号。
- 将平台标准 JSON 响应封装为同一契约的 XML，并写回 ESB 响应链路。
- Kafka 请求和响应主题继续使用结构化 JSON，不承载 ESB XML 协议转换职责。

## 接口

```text
POST /esb/forward
Content-Type: application/xml
Accept: application/xml
```

正常请求返回 `application/xml`。联调页面使用 `?diagnostic=true` 获取 JSON 诊断对象，其中同时包含 `standardResponse` 和最终 `xmlResponse`。

Gateway 授权环境由 `edsp.esb.environment` 配置，默认 `TEST`；生产环境应在 Nacos 中明确设置为 `PROD`，不从业务请求报文接受环境覆盖。

生产请求至少包含：

```xml
<Service>
  <Service_Header>
    <service_sn>全局流水号</service_sn>
    <service_id>平台标准服务编码</service_id>
    <requester_id>调用系统编码</requester_id>
    <service_time>yyyyMMddHHmmss</service_time>
    <version_id>01</version_id>
  </Service_Header>
  <Service_Body><request>业务字段</request></Service_Body>
</Service>
```

ESB 适配只负责协议转换，不负责业务字段映射、供应商加解密或统一返回码；业务执行由目标数据应用在本地完成。

## 构建

```bash
mvn test
mvn package -DskipTests
```
