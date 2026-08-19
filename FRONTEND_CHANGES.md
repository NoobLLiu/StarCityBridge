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

### 2026-08-19 · 市场系统可用性修复：字段对齐 + 数组返回 + 余额展示（市场专项）

**背景**：市场此前“有一点对接但不能用不能看”——返回结构/字段与前端契约不符
（挂单不是数组、盘口字段名不一致、仓库/信息字段对不上、分页 total 缺失），导致页面空白或报错。

**后端变动（stockexchange-patched + StarCityBridge）**
- stockexchange-patched `WebMarketManager`：
  - 字段对齐（新旧并存）：商品 `item_id`(字符串)/`name`/`volume`；挂单 `order_id`/`type`(buy/sell)/`name`；
    成交 `trade_id`/`type`/`time`/`fee`；仓库 `money`(=money_balance) + 物品 `item_id`/`name`；
    信息 `tax_rate`(=tax_rate_percent)/`notice`(=announcement)。
  - `myOrders` 改为**直接返回数组**（前端契约 `MarketOrder[]`）。
  - `myTrades` 分页对齐：返回 `items`/`total`/`page`/`page_size`。
  - `listItems` 增加 `total`（=total_items，修复分页总条数）。
  - `orderBook` 增加 `item_id`/`buys`/`sells`。
  - 新增 `myBalance`：经 **Vault（经济核心，底层 XConomy）** 读取玩家经济余额；`myWarehouse` 也附带
    `balance`/`currency_name`/`economy_available`（经济不可用时 `balance=null`，不报错）。
  - 管理动作（停牌/税率/公告/重载/重连）增加 `admin` 标志守卫（本迭代未暴露 HTTP 路由）。
- StarCityBridge：
  - `MarketModule` 支持数组 data（`my_orders`）、新增 `my_balance` 动作、管理动作透传 `admin`。
  - `HttpApiServer` 新增路由 `GET /api/market/me/balance`。

**API 文档**：`WEB_API.md` 市场段已更新；网页设计详细稿件见 `docs/market-web-design.md`。

**前端修改**：需要，见下方提示词。

### 2026-08-19 · 团队系统全面可用：MGTeam-JE 导出层权限校验 + 字段对齐（团队专项）

**背景**：此前团队接口“有一点对接但不能用不能看”——只读接口无权限校验、字段契约与前端不符、
返回结构不匹配（数组/扁平对象）。本迭代先修团队，领地/市场后续单独处理。

**后端变动（MGTeam-JE + StarCityBridge）**
- MGTeam-JE `WebTeamManager` 重写：
  - 只读接口全部加权限校验：详情/成员/资金/留言=仅本团队成员；申请/流水=仅 operator；`admin=true`（对应 `mgteam.admin`）可越权。
  - `teamMembers` / `teamApplications` 改为**直接返回数组**（不再包 `{ok,data:{...}}`）；无权限时返回 null，由 bridge 转成 `code!=0`。
  - 字段对齐（新旧字段同时保留）：`tid`/`team_id`、`friendly_fire`/`allow_friendly_fire`、`owner`/`owner_uuid`、
    `sender`/`sender_name`、`applicant`/`applicant_uuid`、`type`(存入/取出)+`note`+`amount`、`my_role`(OPERATOR/MEMBER)、`joined_at`(恒 null 占位)。
  - `my_team` 改为**扁平对象**：`{in_team:false}` 或 `{in_team:true, tid, name, my_role, ...}`。
  - 写操作规则与游戏内一致（成长等级/名称唯一/余额/冷却/至少1名管理员/解散确认名），需要玩家在线的返回明确提示。
- StarCityBridge：
  - `TeamModule` 改用新签名并传入调用者 `player_uuid` 与 `admin` 标志（权限校验在 MGTeam 内完成）。
  - `HttpApiServer.dispatch/send` 支持 `data` 为 **JSON 数组**（团队 members/applications 返回数组）。
  - 新增路由：`POST /api/team/:tid/message/read`、`POST /api/team/:tid/notice/read`（标记已读，允许离线）。

**API 文档**：`WEB_API.md` 团队段已更新；网页设计详细稿件见 `docs/team-web-design.md`。

**前端修改**：需要，见下方提示词。

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

### 市场系统对接修复（2026-08-19）

请修复网页平台「市场系统」，使其与游戏内 StockExchange 行为一致。后端 API 文档：
`StarCityBridge/WEB_API.md`（市场段），网页设计详细稿件：`StarCityBridge/docs/market-web-design.md`。
请求头 `Authorization: Bearer <token>`，响应统一 `{code, message, data}`，`code=0` 成功；`player_uuid` 由后端从 token 自动注入。

**关键改动（必须适配）**
1. `GET /api/market/me/orders` 的 `data` 现在是**数组**（不是 `{orders:[...]}`）；请把请求封装/类型改为 `MarketOrder[]`。
2. 字段统一用新名：商品 `item_id`(字符串)/`name`/`volume`；挂单 `order_id`/`type`(小写 buy/sell)/`name`；
   成交 `trade_id`/`type`/`time`/`fee`；仓库 `money` + 物品 `item_id`/`name`；信息 `tax_rate`/`notice`。
3. `GET /api/market/me/trades` 返回 `{items, total, page, page_size}`，可直接用于分页。
4. `GET /api/market/me/warehouse` 新增 `balance`/`currency_name`/`economy_available`；`balance` 可能为 `null`，显示占位。
5. 新增轻量余额接口 `GET /api/market/me/balance`（`{balance, currency_name, economy_available, warehouse_money}`）。

**需要实现的页面/逻辑（详见设计稿件）**
- 行情列表页：出售/求购视角切换、搜索、分页（用 `total`）、点行加载盘口。
- 品种详情/盘口：`buys`/`sells` 聚合档位 + 快捷市价/快速上架；挂单标注本人可撤/取回。
- 挂单弹窗：买/卖、价格/数量校验（后端中文提示）、卖单可选 `item_base64`。
- 我的交易：挂单（数组、可撤销）、成交（分页）、仓库（余额 + 物品 + 存/取仓）。
- 钻石兑换弹窗：d2m/m2d，展示汇率/税率/预计到手；提交 `POST /api/market/exchange`。
- 余额展示：仓库页与兑换页顶部显示经济余额（Vault/XConomy），不可用时显示占位。
- 离线提示：手持存入/提取到背包类操作返回“该操作需要玩家在线”，直接 toast 展示。

**验收**：列表能正常显示商品与分页总条数；我的挂单/成交/仓库能正常显示与操作；
盘口能显示买盘卖盘；兑换与余额展示正常；所有规则错误以后端 message 展示。

### 团队系统对接修复（2026-08-19）

请修复网页平台「团队系统」，使其与游戏内 MGTeam 行为一致。后端 API 文档：
`StarCityBridge/WEB_API.md`（团队段），网页设计详细稿件：`StarCityBridge/docs/team-web-design.md`。
请求头 `Authorization: Bearer <token>`，响应统一 `{code, message, data}`，`code=0` 成功；`player_uuid` 由后端从 token 自动注入，前端无需传。

**关键改动（必须适配）**
1. `GET /api/team/:tid/members` 与 `GET /api/team/:tid/applications` 的 `data` 现在是**数组**（不是对象）；类型/请求封装请支持数组 data。
2. `GET /api/team/me` 现在是**扁平对象**：`{in_team:false}` 或 `{in_team:true, tid, name, my_role, members:[...], ...}`；`my_role` 取值 `OPERATOR`/`MEMBER`，`OPERATOR` 即游戏内“管理员”，用它判断是否显示管理入口。
3. 字段用新名（后端同时保留旧名，推荐统一用新名）：`tid`、`friendly_fire`、`owner`、`sender`、`applicant`、`type`(存入/取出)、`note`、`amount`、`my_role`。
4. 团队详情/成员/资金/留言仅成员可见，申请/流水仅管理员可见；非成员访问会返回 `code!=0` + 中文 message（如“您不在此团队中”“需要管理员权限”），前端直接 toast 展示，不要本地猜权限、不要返回空态误导。

**需要实现的页面/逻辑（详见设计稿件）**
- 未加入态：公开团队排行榜（分页+搜索）、按 ID/名称搜索、创建团队（错误信息直接展示后端中文提示）、申请加入按钮。
- 已加入态主页：团队信息卡（名称/ID/队长/资金/公开/友伤/公告/成员数/我的身份）、成员列表（管理员在前、在线状态）、留言板/资金/管理导航（管理仅 OPERATOR 显示）。
- 留言板：列表（最新在前、分页）+ 发布（≤100字、冷却提示）+ 进入后调用 `POST /api/team/:tid/message/read`。
- 资金页：余额、存入（全员）、取出（仅 OPERATOR）、流水（仅 OPERATOR，类型/金额/原因/前后余额）。
- 管理页（仅 OPERATOR）：成员管理（任命/降级/移出，`target_uuid`）、申请管理（通过/忽略，`applicant_uuid`）、公告编辑、设置（改名/公开/友伤开关）、解散（输入团队名确认）。
- 未读角标：用 `GET /api/team/:tid/message_state`。
- 在线队友：`GET /api/team/online-teammates` 仅作信息展示（不提供传送）。
- 传送相关 UI 全部移除（后端已删除 warp/teleport 导出）。

**验收**：未加入玩家看不到任何团队内部数据；普通成员看不到申请/流水、做不了管理操作；OPERATOR 可完成全部管理；所有错误以后端 message 展示。
