# jsnx-edsp-esb-adapter

ESB 报文适配服务，端口 `8084`。

## 职责

- 接收 ESB 队列或 XML 报文。
- 将外围 XML、队列报文转换为平台统一 JSON 请求。
- 透传全局流水号、机构号、调用系统号和源系统号。
- 将平台标准响应转换回 ESB 所需格式。
- 可通过 Kafka 异步发布原始请求事件和处理结果事件。

## 接口

```text
POST /esb/forward
Content-Type: application/xml
```

ESB 适配只负责协议转换，不负责业务字段映射、供应商加解密或统一返回码；业务执行由目标数据应用在本地完成。

## 构建

```bash
mvn test
mvn package -DskipTests
```
