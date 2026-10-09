# EDSP 2.0 Demo 工程索引

Demo 按可独立交付的工程拆分，不把前端、平台后端和供应商模拟放进同一个构建工程。

```text
demo/
├── jsnx-edsp-platform-backend/   # 平台 Java Maven 聚合工程，IDEA 独立打开
├── jsnx-edsp-platform-frontend/  # 平台 Vue 工程，前端工具独立打开
├── jsnx-edsp-vendor-backend/     # 上游供应商 Java 工程，IDEA 独立打开
├── jsnx-edsp-vendor-frontend/    # 上游供应商 Vue 工程
└── jsnx-edsp-deployment/         # 跨工程启停、日志、PID 和部署文档
```

## 开发工具建议

- IDEA 窗口一：打开 `jsnx-edsp-platform-backend/pom.xml`。
- IDEA 窗口二：按需打开 `jsnx-edsp-vendor-backend/pom.xml`。
- VS Code/WebStorm：分别打开两个前端目录。
- 不要将 `demo/` 根目录作为单个 IDEA Java 工程导入，否则会混入前端依赖和运行产物。

## 一键启动与全链路验证

```bash
cd jsnx-edsp-deployment
./bin/start-demo.sh
./bin/run-full-demo.sh
```

完整请求链为：

```text
调用系统 XML
→ 模拟 ESB 请求队列
→ jsnx-edsp-esb-adapter
→ Spring Cloud Gateway
→ 独立数据源应用
→ 上游供应商模拟
→ 响应字段及返回码标准化
→ 统一 JSON 响应
```

当前提供企业工商、个人征信和百融反欺诈三个独立数据应用。详细端口、环境变量和停止命令参见 `jsnx-edsp-deployment/README.md`。
