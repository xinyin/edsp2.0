# IntelliJ IDEA 开发说明

## 导入工程

1. IDEA 选择 `Open`。
2. 打开 `jsnx-edsp-platform-backend/pom.xml`。
3. Project SDK 和 Maven Runner JRE 都选择 JDK 17。
4. 等待 Maven 导入全部模块，不要逐个打开子模块。

平台前端、供应商后端、供应商前端仍是独立工程，应分别打开，不要加入本 Maven 工程。

## 模块分类

- `common-components`：普通 JAR，只运行单元测试，不启动 Spring Boot。
- `platform-services`：平台公共服务，每个模块都有独立 `main` 类。
- `data-applications`：按数据源隔离的业务应用，可独立调试、发布和扩容。

## 本地启动顺序

1. MySQL 和供应商 Demo。
2. `EdspDemoApplication`、`CapabilityRuntimeApplication`。
3. `EnterpriseDataApplication`、`CreditDataApplication`、`ObservabilityApplication`。
4. 联调通过 Admin 的 `/api/interfaces/{id}/test` 完成；生产链路还需启动 `CapabilityRuntimeApplication`。
5. `GatewayApplication`、`EsbAdapterApplication`。
6. 平台前端。

也可以从独立部署工程执行 `bin/start-vendor.sh` 和 `bin/start-platform.sh`。

## 新增数据应用

1. 复制 `data-applications/jsnx-edsp-data-enterprise` 的工程骨架。
2. 修改 artifactId、Spring 应用名、端口和数据源编码。
3. 在根 `pom.xml` 的 `<modules>` 中登记。
4. 在 Admin 数据源管理中登记应用名和行内 VIP（`runtime_url`）；Gateway 根据已发布的
   `apiCode + Method + Path` 动态路由，不需要修改 Gateway 配置。所属数据应用负责加载和热切换计划。
5. 独立编写单元测试和联调样例，不修改其他数据应用。

## 模块构建

```bash
# 全部测试
mvn test

# 单模块及其依赖
mvn -pl data-applications/jsnx-edsp-data-credit -am test

# 跳过测试打包全部可执行应用
mvn clean package -DskipTests
```
