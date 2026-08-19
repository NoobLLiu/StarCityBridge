# StarCityBridge 前端修改说明（Frontend Change Log）

本文件由后端（StarCityBridge 插件）改动驱动，随每次后端提交**同步更新**：

- 后端 API / 行为变动 → 记录在「变动记录」，供前端联调参考。
- 需要前端跟着改 → 汇总成「前端修改 AI 提示词」，可直接发给负责前端重写的 AI。

## 当前基线（截至 2026-08-19）

- 后端 = StarCityBridge 插件内建 HTTP REST API（`web-api` 段，默认端口 8083），前端通过 `VITE_API_BASE` 直连（当前配置 `http://activity.mgdlmc.top:8083/api`）。
- 前端仓库：`NoobLLiu/StarCity-web-Remake`。
- 前端已删除对旧 Go 后端 / WebSocket 的依赖，仅使用 HTTP REST。
- 管理后台仅保留工单功能（`/api/admin/tickets`）。
- 完整接口文档见 `WEB_API.md`。

## 变动记录

（按时间倒序；无前端影响的条目也会记录后端变动，便于追溯）

### 2026-08-19 · 移除旧 WebSocket 网页后端对接（commit 7a115e3）

**后端变动**
- 删除插件与旧网页后端之间的 WebSocket 通信（`ws/` 包：`WsClient`/`WsServer`/`Message`），删除主类 `request()/sendEvent()` 接口。
- 删除 `/site resetpw` 命令（原针对旧后端）；`/site bind` 改为直接调用本地 authme 模块。
- 配置移除 `backend:` / `connection:` / `server:` 段，仅保留 `web-api:`（HTTP REST）与其它模块配置。

**API 变动**：无。HTTP REST 路由（`/api/...`）保持不变，响应格式不变。

**前端修改**：无。

---

## 前端修改 AI 提示词

> 当某次后端改动需要前端配合时，在此处汇总一段可直接发送的提示词，说明：
> 1. 后端现状与 API 文档位置（`WEB_API.md`）；
> 2. 本次改动的接口/字段变动；
> 3. 前端需要修改的页面、组件、状态管理或请求封装。