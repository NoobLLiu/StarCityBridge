# StarCityBridge 更新说明

## 本版本内容

### 网页后端重大变更
- 网页后端改由 StarCityBridge 插件直接承担（内建 HTTP REST API，默认端口 8083），前端不再经过独立 Go 后端。
- 统一响应格式 `{ code, message, data }`；玩家端 Bearer token 鉴权（AuthMe 邮箱密码登录签发）；管理端 `X-Admin-Token`。
- 前端对接文档见仓库 `WEB_API.md`。

### 新增：Residence 领地对接（不改 Residence 本体）
- 只调用 Zrips/Residence 已有公开接口，覆盖：
  - 领地信息：列表（分页/搜索/按主人/只看我的）、详情（区域边界、子领地、权限、租售、银行、进出提示语）
  - 权限编辑：领地 flag、玩家 flag（设置/移除/清空）、重置默认权限、进出提示语编辑
- 并发保护：所有读写强制主线程串行执行，写操作按领地加锁，避免网页与游戏内同时操作导致数据异常。
- 写接口仅限领地主人/父领地主人；玩家从未上线等异常一律捕获并返回中文提示。

### 其他
- 管理后台按需求只保留工单功能（创建/列表/详情/回复/关闭）。
- 团队（MGTeam-JE）传送/锚点导出已移除：在线玩家功能不适合网页后端。
- 云端构建流水线：Residence/AuthMe/MGTeam/StockExchange 均从 GitHub 仓库源码先行构建，再编译本插件。

## 运行要求

- Paper 1.21.11+，Java 25
- 依赖插件：AuthMe（fork 5.7.0-FORK）、StockExchange、MGTeam、Residence 6.0.2.x
- 首次部署请修改 `config.yml` 中 `web-api.token-secret` 与 `web-api.admin-token`

## 部署

1. 将 `StarCityBridge.jar` 放入服务器 `plugins/` 目录
2. 启动一次生成配置，修改 `web-api` 段的密钥与端口
3. 重启服务器，前端通过 `http://<服务器>:8083/api` 访问
