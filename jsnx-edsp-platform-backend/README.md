# EDSP 2.0 平台后端

外部数据平台后端 Maven 聚合工程，不包含平台前端和供应商 Demo。使用 IntelliJ IDEA 时打开本目录根 `pom.xml`，项目 SDK 选择 JDK 17。

## 目录结构

```text
jsnx-edsp-platform-backend/
├── common-components/
│   ├── jsnx-edsp-contracts             # 稳定 DTO 和服务契约
│   ├── jsnx-edsp-client-sdk            # 数据应用本地执行器、Plan 加载和上游客户端
│   ├── jsnx-edsp-plan-core             # PlanSnapshot、映射和原子切换基础能力
│   ├── jsnx-edsp-transform-core        # 字段转换能力
│   ├── jsnx-edsp-crypto-core           # 加解密能力
│   ├── jsnx-edsp-signature-core        # 签名能力
│   ├── jsnx-edsp-response-core         # 统一响应能力
│   ├── jsnx-edsp-log-core              # Trace 和异步日志上下文
│   └── jsnx-edsp-platform-core         # Admin 领域服务和发布逻辑
├── platform-services/
│   ├── jsnx-edsp-data-gateway          # 8080，认证、授权、入口治理和一级路由
│   ├── jsnx-edsp-data-admin            # 8081，配置、审批、授权和计费
│   ├── jsnx-edsp-data-observability    # 8083，调用日志、Trace 和监控
│   ├── jsnx-edsp-esb-adapter           # 8084，XML/JSON 转换和模拟 ESB 队列
│   └── jsnx-edsp-capability-runtime    # 8091，可选复杂能力服务，非必经节点
├── data-applications/
│   ├── jsnx-edsp-data-enterprise       # 8181，企业工商独立应用
│   ├── jsnx-edsp-data-credit           # 8182，征信独立应用
│   └── jsnx-edsp-data-antifraud        # 8183，百融反欺诈独立应用
└── pom.xml
```

## 正常业务调用链

```text
调用系统 XML
→ 模拟 ESB 请求队列
→ jsnx-edsp-esb-adapter
→ jsnx-edsp-data-gateway
→ 目标独立数据源应用
→ 本地 PlanSnapshot 完成字段映射/安全处理
→ 上游供应商
→ 本地响应标准化
→ 统一 JSON 响应
```

Gateway 不执行字段映射、上游协议适配和业务编排，也不把请求统一转发到集中式 Runtime。每个数据源应用均为独立 JVM、独立发布单元和独立故障边界。

## 执行计划与不停机生效

1. Admin 将接口、字段映射、统一返回码、安全策略和超时配置持久化到 MySQL。
2. 审批发布后生成不可变发布快照，并通知对应数据源应用刷新。
3. 数据源应用通过 Admin 拉取快照，在本 JVM 中使用 `AtomicReference` 原子替换。
4. 新请求立即使用新版本，在途请求继续使用旧版本。
5. 应用重启后重新从 MySQL 发布快照恢复，不依赖易失内存持久化。

## 依赖规则

```text
数据源应用 → contracts / client-sdk / 必需 Core
平台服务   → contracts / 对应内部 Core
能力服务   → 按需复杂能力实现
```

- 禁止微服务 A 通过 Maven 依赖微服务 B。
- 禁止数据源应用互相依赖。
- Capability Runtime 仅用于按需复杂场景，不是普通同步链路必经节点。
- Router 只预留契约和配置字段，本 Demo 不实现业务 Router。
- Kafka/监控事件采用失败可丢失、绝不阻断主请求的策略。

## 构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn clean package -DskipTests
```

## 生产安全转换

请求映射和独立数据应用共用同一套执行引擎，支持：

- `AES_256_GCM`：256 位密钥、96 位随机 IV、128 位认证标签，密文格式为 `v1.<iv>.<ciphertext+tag>`。
- `HMAC_SHA256`：密钥不得少于 32 字节，支持 HEX 或 Base64 输出。
- `SM4_GCM`、`SM2`、`HMAC_SM3`、`SM2_SM3`：基于 Bouncy Castle 1.78 的国密实现。
- `RSA_SHA256`：使用 PKCS#8 私钥执行数字签名。
- 密钥配置只允许 `config://`、`env://`、`sys://` 引用，接口配置和发布计划不保存明文密钥。
- 自定义算法通过 `SecurityAlgorithm` SPI 和独立扩展 JAR 注册，详见 `docs/CUSTOM_SECURITY_ALGORITHM.md`。

`config://` 从 Spring Environment 解析，因此可以由 Nacos Config 注入。例如页面填写：

```text
config://edsp.security.keys.enterprise-encryption
config://edsp.security.keys.enterprise-signing
```

本地启动前注入一个 Base64 编码的 32 字节密钥：

```bash
export EDSP_ENTERPRISE_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export EDSP_ENTERPRISE_SIGNING_KEY="$(openssl rand -base64 32)"
```

生产环境应由行内密钥管理系统或受控的 Nacos 加密配置注入，并建立密钥轮换、双人复核和访问审计；不得把示例密钥写入 Git、数据库或启动脚本。

仅构建单个应用及其依赖：

```bash
mvn -pl data-applications/jsnx-edsp-data-enterprise -am package -DskipTests
```

本地一键部署和演示参见 `../jsnx-edsp-deployment/README.md` 与 `../jsnx-edsp-deployment/DEMO_RUNBOOK.md`。
