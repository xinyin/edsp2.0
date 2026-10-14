# 数据应用工程生成与 Nacos 同步

数据资源接入 → 选择数据源 → 数据处理应用路由 → 保存、生成并同步。

处理应用标识同时作为 Maven artifactId、Spring 应用名、生成目录名和 Nacos Data ID 前缀。
例：`jsnx-edsp-data-credit-v2` → `jsnx-edsp-data-credit-v2.yaml`。

## 服务端配置

Admin 的 `edsp.code-generation.enabled` 控制是否生成。
`edsp.code-generation.output-root` 设置服务器生成根目录，默认 `/data/edsp/generated-applications`。
推荐通过 `EDSP_CODE_GENERATION_ROOT` 环境变量覆盖，Admin 运行账号须有该目录的读写权限。
本地三个启停/重载脚本默认设置为 `jsnx-edsp-deployment/var/generated-applications`。
生成代码不直接写入正式项目，不自动修改父 POM，不自动编译或启动应用。

Nacos 发布使用 Admin 当前 config 的 server-addr、namespace、group 和凭据。
账号需要对应命名空间/Group 的读写配置权限。生成源码不保存任何账号密码。
同一个 Data ID 不应用于多个环境的不同配置；使用环境隔离的 namespace/Group。

## 操作结果

1. MySQL：`data_source.application_name`、`runtime_url` 更新，配置版本递增。
2. Gateway：已提交路由后触发刷新；未连通实例会按其既有定时刷新策略重试。
3. 工程：从 Admin JAR 内固定模板生成启动类、Controller、属性类、配置、测试、POM 和 README。
4. Nacos：首次初始化 Data ID；若已有配置，仅以 CAS 更新平台管理的 `server.port`，其他生产配置保持不变。

代码生成使用临时目录及原子移动。已有目录必须有匹配应用名与数据源编码的生成标识，否则拒绝覆盖。
重复保存会复用已有工程；开发人员修改不会丢失。输出根目录须仅允许受信任的部署账号写入。
多 Admin 实例建议各自使用独立生成根目录，或由统一文件系统服务提供生成任务，避免多个进程竞争同一目录。

路由保存成功但生成/Nacos 失败时返回分步骤结果；修复目录权限或 Nacos 连接/权限后重新执行。
HTTP 请求中断后也可重新执行，已有工程不会覆盖。完整异常保存在 Admin 日志，不把敏感异常原文展示在页面。

## 后续开发和发布

复制结果目录到后端 `data-applications`，加入父 POM modules，按生成 README 的步骤开发、测试并提交 Git。
JDK 为 17。页面分别填写 Gateway 路由地址和应用监听端口；监听端口作为 Nacos `server.port` 的默认值，仍可由部署环境变量 `SERVER_PORT` 覆盖。

Gateway 路由地址在本地可以是单实例地址。生产环境通常填写行内 VIP：由 F5 或其他负载均衡设备提供的稳定虚拟地址，后面挂载同一数据应用的多个 JVM 实例。Gateway 访问 VIP，VIP 再把流量分配到健康实例。VIP 对外端口与后端 JVM 监听端口可能不同，因此页面将两者独立配置，不能仅从 VIP 推断监听端口。
完成打包部署、健康检查后再发布和授权平台 API。页面保存只登记路由和初始化工程，不代表应用已经部署。

普通接口配置、字段映射和返回码继续由 MySQL 中的版本化执行计划管理；Nacos 管理应用运行配置。
本功能不是把每一个 API 配置翻译为 Java 方法，而是生成可运行的独立数据应用骨架。
