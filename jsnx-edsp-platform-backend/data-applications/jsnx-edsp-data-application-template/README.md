# 资源提供商处理应用模板

本目录是新增资源提供商处理应用的可运行骨架，不是生产应用，不应直接部署。一个资源提供商复制并部署一个应用，其下多个数据源和 API 共用该应用；标准执行链路继续复用 `jsnx-edsp-client-sdk`。

## 已提供的能力

- 从 Nacos加载应用配置并注册服务实例。
- `POST /internal/adapter/published`：执行已发布计划。
- `POST /internal/adapter/execute`：上游能力草稿联调。
- `POST /internal/adapter/plans/{interfaceCode}/refresh`：刷新本地不可变执行计划。
- `DELETE /internal/adapter/plans/{interfaceCode}/refresh`：停用本地执行计划。
- `GET /internal/adapter/info`：返回应用名、资源提供商编码和运行状态。
- Actuator 健康检查、指标采集和调用事件上报。

## 复制示例

以新增征信 2.0 应用为例：

```bash
cd jsnx-edsp-platform-backend/data-applications
cp -R jsnx-edsp-data-application-template jsnx-edsp-data-credit-v2
```

完成以下替换：

| 模板值 | 替换值示例 | 位置 |
| --- | --- | --- |
| `jsnx-edsp-data-application-template` | `jsnx-edsp-data-credit-v2` | `pom.xml` 的 `artifactId` |
| `jsnx-edsp-data-template` | `jsnx-edsp-data-credit-v2` | `application.yml`、Nacos YAML |
| `com.jsnx.edsp.data.template` | `com.jsnx.edsp.data.creditv2` | Java目录和 `package` |
| `TemplateDataApplication` | `CreditV2DataApplication` | 启动类文件名和类名 |
| `TEMPLATE` | `CREDIT_PROVIDER` | Nacos YAML 的 `provider-code` |
| `独立数据应用样例` | `征信2.0` | Nacos YAML 的 `display-name` |
| `8199` | 分配的唯一端口 | Nacos YAML 的 `server.port` |

`TemplateAdapterController` 可以保留通用实现，也可以同步重命名。不要修改标准内部接口路径，否则 Admin 和 Gateway 无法按统一协议调用该应用。

## 接入工程和Nacos

1. 将新模块加入后端父工程 `pom.xml` 的 `<modules>`：

   ```xml
   <module>data-applications/jsnx-edsp-data-credit-v2</module>
   ```

2. 将模块内 `config/nacos/jsnx-edsp-data-template.yaml` 重命名为正式 Data ID，并复制到：

   ```text
   jsnx-edsp-deployment/config/nacos/data/jsnx-edsp-data-credit-v2.yaml
   ```

3. 发布新 Data ID：

   ```bash
   cd jsnx-edsp-deployment
   ./bin/sync-config-to-nacos.sh
   ```

4. 构建并测试：

   ```bash
   mvn -pl data-applications/jsnx-edsp-data-credit-v2 -am clean verify
   ```

5. 启动应用后验证：

   ```bash
   curl --fail http://127.0.0.1:8192/actuator/health
   curl --fail http://127.0.0.1:8192/internal/adapter/info
   ```

6. 在平台“资源方管理 → 处理应用路由”中登记：

   ```text
   处理应用标识：jsnx-edsp-data-credit-v2
   运行地址：http://127.0.0.1:8192
   ```

生产环境的运行地址填写该资源提供商处理应用的 F5/VIP，不绑定单个实例地址。

## 何时需要编写Java代码

普通 HTTP 接口、字段映射、码值转换、通用签名和加解密均通过平台配置完成，不修改本模板。只有供应商 SDK、特殊协议或无法配置化的算法存在时，才在新模块中增加定制 Bean；定制代码不得改变统一发布入口和计划刷新协议。

## 上线检查

- 应用名、Nacos Data ID、`spring.application.name` 三者一致。
- `edsp.data-application.provider-code` 与平台 `data_provider.provider_code` 完全一致；该提供商下的数据源共用此应用，不再逐数据源绑定应用。
- 端口未被其他进程占用，启停脚本已登记新应用。
- Nacos 配置已发布，服务列表中存在健康实例。
- 平台运行地址健康检查通过，动态路由可解析。
- 未在 YAML、源码或 Git 中保存真实密码、密钥和令牌。
- 标准编排、发布前链路和授权调用均验证通过。
