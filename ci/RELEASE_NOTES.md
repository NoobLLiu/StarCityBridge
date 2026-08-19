# StarCityBridge 更新说明

## 本版本内容

### 网页后端重大变更
- 网页后端改由 StarCityBridge 插件直接承担（内建 HTTP REST API，默认端口 8083），前端不再经过独立 Go 后端。
- 统一响应格式 `{ code, message, data }`；玩家端 Bearer token 鉴权（AuthMe 邮箱密码登录签发）；管理端 `X-Admin-Token`。
- 前端对接文档见仓库 `WEB_API.md`。

### 领地系统全面可用（页面参考 ResidenceList，接口仅用 Residence 本体公开 API，不改其任何代码）
- 只调用 Zrips/Residence 已有公开接口，覆盖：
  - 领地信息：列表（可见性过滤/分页/搜索/按主人/只看我的）、详情（区域边界、子领地、权限、租售、银行、进出提示语、可见性/可管理性、传送坐标[在线时]）
  - 权限编辑：领地 flag（按 9 大分类返回、三态 true/false/remove）、玩家 flag（设置/移除/清空）、重置默认权限、镜像权限、进出提示语编辑
  - 管理操作：重命名、删除（二次确认）、转让（发起者+接收者须在线）、出售挂牌/取消出售、出租设置/取消出租/租用/退租/支付租金
  - 市场：出售中+可租领地浏览、我的租用列表
- 可见性即权限：列表/详情与游戏内一致（服务器领地/非 hidden/本人拥有/管理员），隐藏领地不可见。
- 离线容错：旗标/重命名/镜像/出售挂牌等允许离线；购买/出租/租用/退租/支付租金/转让因 Residence API 只接受在线 Player，离线时返回明确中文提示。
- 并发保护：所有读写强制主线程串行执行，写操作按领地加锁，避免网页与游戏内同时操作导致数据异常。
- 写接口仅限领地主人/父领地主人/管理员；玩家从未上线等异常一律捕获并返回中文提示。
- 网页设计稿见 `docs/residence-web-design.md`；前端提示词见 `FRONTEND_CHANGES.md`。

### 其他
- 管理后台按需求只保留工单功能（创建/列表/详情/回复/关闭）。
- 团队（MGTeam-JE）传送/锚点导出已移除：在线玩家功能不适合网页后端。
- 市场系统（StockExchange）字段对齐 + Vault/XConomy 余额展示（见 `docs/market-web-design.md`）。
- 云端构建流水线：Residence/AuthMe/MGTeam/StockExchange 均从 GitHub 仓库源码先行构建，再编译本插件。

## 运行要求

- Paper 1.21.11+，Java 25
- 依赖插件：AuthMe（fork 5.7.0-FORK）、StockExchange、MGTeam、Residence 6.0.2.x
- 首次部署请修改 `config.yml` 中 `web-api.token-secret` 与 `web-api.admin-token`

## 部署

1. 将 `StarCityBridge.jar` 放入服务器 `plugins/` 目录
2. 启动一次生成配置，修改 `web-api` 段的密钥与端口
3. 重启服务器，前端通过 `http://<服务器>:8083/api` 访问
