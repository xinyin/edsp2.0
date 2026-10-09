# EDSP 上游供应商模拟前端

独立 Vue 3 + TypeScript + Element Plus 工程，开发端口 `5190`。本工程拥有自己的依赖，不复用平台前端的 `node_modules`。

## 功能模块

- 数据源管理：新增、编辑、停用供应商数据源。
- API 管理：在数据源下新增 API，配置实时服务路径、请求样例和返回报文。
- 企业数据维护：维护企业基础数据，用于验证命中、未命中和错误请求场景。
- 开放目录：将当前有效数据源和 API 暴露到 `/vendor/open/catalog`。

供应商页面的新增或修改保存后，平台刷新开放目录即可发现变化，无需重启供应商应用或平台应用。

```bash
npm install
npm run dev
npm run build
```
