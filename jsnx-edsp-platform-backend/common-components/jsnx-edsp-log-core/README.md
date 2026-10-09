# jsnx-edsp-log-core

统一 Trace ID、结构化日志事件、敏感字段脱敏和非阻塞日志队列基础组件。具体 Kafka、文件或其他传输由应用层注入 sink；队列满、传输失败时只丢弃并计数，不阻断主调用链。

Gateway 使用 `GatewayLogGlobalFilter` 采集入口元数据，使用 `GatewayLogPublisher` 注入 Kafka sink。Gateway 不在日志组件中同步读取请求体，避免响应式请求体缓存放大内存；ESB 或数据应用负责记录脱敏后的原始报文。
