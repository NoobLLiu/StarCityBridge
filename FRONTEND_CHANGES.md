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

### 2026-08-19 · 领地系统全面可用：Residence 本体公开 API 全量对接（领地专项）

**背景**：此前领地只有「列表/详情/旗标编辑」基础能力，缺少市场（买卖/租借）、重命名、镜像、
删除、转让、我的租用等与游戏内 ResidenceList 对应的功能。本迭代以 ResidenceList 页面为设计参考，
接口完全使用 Residence（Zrips/Residence）本体公开 API，**未改动 Residence 本体任何代码/接口**。

**后端变动（仅 StarCityBridge）**
- `ResidenceBridgeModule` 新增动作：
  - 只读：`market`（出售中+可租列表）、`my_rents`（我租用的领地）。
  - 写（无需玩家在线）：`rename`（重命名）、`mirror_perms`（镜像权限）、`delete`（删除，二次确认）、
    `sell`（出售挂牌）、`unlist_sell`（取消出售）、`unlist_rent`（取消出租挂牌）。
  - 写（需玩家在线，Residence API 只接受 `Player` 对象，离线返回明确提示）：
    `buy`（购买）、`set_rent`（出租设置）、`rent`（租用）、`unrent`（退租/强制退租）、
    `pay_rent`（支付租金/续租）、`transfer`（转让，发起者+接收者均须在线）。
  - 列表/详情可见性过滤（与游戏内一致）：服务器领地 / 非 `hidden` 领地 / 本人拥有 / 管理员可见；
    详情新增 `viewable`/`can_manage`/`hidden`/`is_server_land`/`economy_enabled`/`rent_system_enabled`/
    `teleport`（玩家在线时）。
  - `flags` 接口新增 `categories[]` 分类结构（参考 ResidenceList 分组，含 `value` 三态/默认值/描述/可编辑性）。
- `HttpApiServer` 新增 14 条领地路由（见 `WEB_API.md`）；动态路径参数增加 URL 解码（领地/团队名含空格可正常访问）。

**API 文档**：`WEB_API.md` 领地段已更新；网页设计详细稿件见 `docs/residence-web-design.md`。

**前端修改**：需要，见下方提示词。

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

### 领地系统对接实现（2026-08-19）

请为网页平台实现「领地系统」页面。后端 API 文档：`StarCityBridge/WEB_API.md`（领地段），
网页设计详细稿件：`StarCityBridge/docs/residence-web-design.md`。
请求头 `Authorization: Bearer <token>`，响应统一 `{code, message, data}`，`code=0` 成功；
`player_uuid` 由后端从 token 自动注入，前端无需传；管理员（is_op）请求自动带 `admin=true`。

**关键规则（必须适配）**
1. **玩家离线限制**：购买/出租设置/租用/退租/支付租金/转让 在玩家离线时返回 `code!=0` + 中文提示
   （如"请先登录服务器"），前端直接展示 message；转让还要求接收者在线，同样展示后端提示。
2. **flag 三态**：`value` 为 `true`/`false`/`null`（未设置=跟随默认）。操作映射：
   允许=`true`、拒绝=`false`、恢复默认=`remove`。权限页优先用 `categories[]` 分组渲染。
3. **可见性即权限**：列表/详情已由后端过滤（隐藏领地不可见）；详情对不可见领地返回
   `code!=0`（"领地不存在或无权查看"），前端直接展示，不要本地猜权限。
4. **子领地路径**：`父领地.子领地`（如 `res1.sub2`）作为 `:residence` 路径参数，前端需 URL 编码。
5. **二次确认**：删除领地（传 `confirm:true`）、重置默认权限必须弹窗确认；删除不可逆。
6. **经济未启用**：`economy_enabled=false` / `rent_system_enabled=false` 时隐藏购买/出租按钮并提示。
7. **列表分页**：`GET /api/residences` 返回 `residences[]` + `total`；`GET /api/residences/market`
   返回 `items[]` + `total`；`GET /api/residences/me/rents` 返回 `rents[]`。

**需要实现的页面/逻辑（详见设计稿件）**
- 领地列表页：所有领地 / 我的领地（`mine=true`）切换、搜索（名称/主人）、分页；
  出售/出租角标；入口：领地市场、我的租用；"创建领地"按钮提示需游戏内选区。
- 领地详情页（他人领地只读）：基本信息、区域边界坐标、子领地列表（可点入）、受信玩家、
  进出提示、租售状态卡（在售→购买按钮 / 可租→租用按钮，本人拥有时进入管理页）。
- 领地管理页（`can_manage=true` 显示）：重命名、进出提示、全局/玩家权限入口、
  重置默认权限（确认）、镜像权限（输入源领地）、出售/取消出售、出租设置/取消出租/强制退租、
  转让（输入目标玩家）、删除（二次确认）。
- 全局权限页：分类分组 → flag 列表（允许/拒绝/未设置三态 + 默认值 + 描述 + 可编辑性），
  点击切换 true/false/remove。
- 玩家权限页：受信玩家列表 → 单玩家 flag 列表（同全局交互）+ 移除单个/清空全部权限。
- 领地市场页：出售中分区（主人/价格/大小→购买）、可租分区（主人/日租金/租期/可续租→租用）。
- 我的租用页：我租用的领地（到期时间/自动支付）+ 续租（pay-rent）/ 退租（unrent）。

**不做**：创建领地（需游戏内选区）、传送/设置传送点（仅展示坐标）、
领地图标/昵称/描述/评分/置顶（ResidenceList 扩展数据，本后端不提供）。

**验收**：访客只能看到可见领地；非主人无法进入管理页/做写操作；主人可完成
重命名/旗标/出售/出租/转让/删除全流程；离线时购买/出租/转让返回明确提示；所有错误以后端 message 展示。

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
