# jsnx-edsp-data-provider-9494

平台生成的 JDK 17 资源提供商独立处理应用。提供商编码：`9494`，Java 包：`com.jsnx.edsp.data.provider9494`。

## 接入正式工程

1. 将本目录复制到后端工程 `data-applications/jsnx-edsp-data-provider-9494`，不要复制 target 目录。
2. 在后端父 pom.xml 的 modules 中加入 `data-applications/jsnx-edsp-data-provider-9494`。
3. 配置 Nacos Data ID `jsnx-edsp-data-provider-9494.yaml`（平台已尝试同步，结果以页面为准）。
4. 当前 Nacos 默认监听端口为 `18883`；生产可通过 SERVER_PORT 环境变量覆盖。
5. 使用 JDK 17 执行 `mvn -pl data-applications/jsnx-edsp-data-provider-9494 -am clean verify`。
6. 将代码提交 Git，按行内流程打包、部署，检查 `/actuator/health` 和 `/internal/adapter/info`。
7. 页面登记的运行地址是 http://127.0.0.1:18883；部署必须与此地址或 VIP 后端一致。

本地启动时请从本模块目录运行 JAR，并检查 `/internal/adapter/info` 返回当前应用标识。不要从部署工程目录直接运行 JAR；Spring Boot 会加载该目录下 Nacos 服务端的 `config/nacos/application.properties`，使数据应用继承 `/nacos` 上下文路径。也可在部署工程运行 `./bin/start-data-application.sh jsnx-edsp-data-provider-9494 18883`。

## 开发与配置

普通接口通过平台配置、保存和发布执行计划，配置更新无需重启数据应用。
特殊供应商协议、SDK 或算法在本模块内扩展，保持内部 adapter 接口契约不变。
源码及 YAML 不保存密码和密钥，使用部署环境变量或受控密钥引用。
平台重复保存仅复用本生成目录，不覆盖开发人员修改过的文件；Nacos 仅同步平台管理的监听端口。
生成工程不代表已部署，必须完成开发、测试、打包和上线流程。
