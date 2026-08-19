# StarCityBridge 插件内建网页后端 API（前端对接文档）

> 本版本起，网页后端由 StarCityBridge 插件直接对外提供，前端不再经过 Go 后端转发。
> 基地址：`http://<MC服务器>:8083/api`（端口见插件 `config.yml` 的 `web-api.port`）。

## 通用约定

- 响应统一为 `{ code, message, data }`，`code = 0` 表示成功；非 0 时 `message` 为中文错误原因。
- 玩家接口：请求头带 `Authorization: Bearer <玩家token>`。
- 管理接口：请求头带 `X-Admin-Token: <admin_token>`（或 query `admin_token=`）；OP 玩家的 Bearer token 也可通过管理接口。
- 玩家 UUID 统一用 `player_uuid` 字段，取自登录时签发的 token，调用方无需自己拼。
- 方法：GET / POST；POST 请求体为 JSON。
- 在线要求：部分市场/团队操作需要对应玩家在线，插件会返回明确的“需要玩家在线”中文提示。

---

## 认证

### POST /auth/login
Body：`{ "email": "xx@xx.com", "password": "..." }`
成功返回：
```json
{ "code":0, "message":"success", "data":{ "token":"v1...", "player":"name", "player_uuid":"...", "email":"...", "is_op":false } }
```

### GET /auth/me
Header `Authorization: Bearer <token>`，返回当前玩家信息。

### GET /health
公开健康检查：`{ status:"ok", plugin:"StarCityBridge", version:"0.1.0", online_players:n }`

### GET /settings/public
公开站点信息：`{ server_name:"StarCity", web_backend:"plugin", plugin_version:"..." }`

---

## 市场（module=market）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/market/items?buy_page=&query=&page=&page_size= | 商品分页列表 |
| GET | /api/market/items/:item_id?buy_page=&page=&page_size= | 单品种详情 |
| GET | /api/market/orderbook/:item_id | 盘口 |
| GET | /api/market/info | 市场参数与公告 |
| GET | /api/market/me/orders | 我的挂单 |
| GET | /api/market/me/trades?page=&size= | 我的成交 |
| GET | /api/market/me/warehouse | 我的仓库 |
| POST | /api/market/order | 挂单：`{type:"buy"|"sell", item_id, price, quantity?, item_base64?}` |
| POST | /api/market/order/:order_id/cancel | 撤单：`{admin?:bool}` |
| POST | /api/market/trade | 市价：`{type:"market_buy"|"market_sell"|"quick_sell", item_id, quantity?}` |
| POST | /api/market/warehouse/deposit | 存仓：`{type:"money", amount} 或 {type:"hand", quantity?}` |
| POST | /api/market/warehouse/withdraw | 取仓：`{type:"all"|"money"|"item", amount?, item_base64?}` |
| POST | /api/market/exchange | 兑换：`{type:"d2m"|"m2d"}` |

## 团队（module=team，传送相关已去除）

> 权限说明（与游戏内一致，校验在 MGTeam-JE 插件内完成，调用方自动携带登录玩家 `player_uuid`）：
> - 团队详情 / 成员 / 资金 / 留言：仅**本团队成员**可见；
> - 申请列表 / 资金流水 / 管理操作：仅**团队管理员(operator)**可看/可做；
> - 排行榜 / 搜索：仅公开团队（私密团队只能按 ID 精确搜索到，详情仍不可见）；
> - 写操作要求玩家在线时返回“该操作需要玩家在线”；只读与“标记已读”允许离线。
> - 未登录/无权限访问：返回 `code!=0` 与中文原因（如“您不在此团队中”“需要管理员权限”）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/team?page=&page_size=&query= | 公开团队排行榜（分页） |
| GET | /api/team/me | 我的团队（**扁平对象**：`{in_team:false}` 或 `{in_team:true, tid, name, my_role, ...}`） |
| GET | /api/team/search?query= | 团队搜索（名称/ID 包含匹配） |
| GET | /api/team/online-teammates | 在线队友（信息性，无传送） |
| GET | /api/team/:tid | 团队详情（含 members 数组；仅成员） |
| GET | /api/team/:tid/members | 成员列表（**data 为数组**；仅成员） |
| GET | /api/team/:tid/applications | 待处理申请（**data 为数组**；仅 operator） |
| GET | /api/team/:tid/funds | 团队资金（仅成员） |
| GET | /api/team/:tid/logs?page=&page_size= | 资金流水（仅 operator） |
| GET | /api/team/:tid/messages?page=&page_size= | 团队留言（仅成员） |
| GET | /api/team/:tid/message_state | 留言/公告未读状态（仅成员） |
| POST | /api/team/create | `{name}` |
| POST | /api/team/:tid/join | 申请入队 |
| POST | /api/team/:tid/application/accept | `{applicant_uuid}` |
| POST | /api/team/:tid/application/reject | `{applicant_uuid}` |
| POST | /api/team/:tid/member/promote | `{target_uuid}` |
| POST | /api/team/:tid/member/demote | `{target_uuid}` |
| POST | /api/team/:tid/member/remove | `{target_uuid}` |
| POST | /api/team/quit | 退出团队 |
| POST | /api/team/:tid/rename | `{name}` |
| POST | /api/team/:tid/notice | `{notice}` |
| POST | /api/team/:tid/public | `{public:bool}` |
| POST | /api/team/:tid/friendly-fire | `{allow:bool}` |
| POST | /api/team/:tid/disband | `{confirm_name}`（需与团队名一致） |
| POST | /api/team/:tid/funds/deposit | `{amount}` |
| POST | /api/team/:tid/funds/withdraw | `{amount}` |
| POST | /api/team/:tid/message | `{content}`（≤100字，有冷却） |
| POST | /api/team/:tid/message/read | 标记留言已读（允许离线） |
| POST | /api/team/:tid/notice/read | 标记公告已读（允许离线） |

团队对象字段（`teamView`，列表/详情/我的团队均含）：`tid`(=`team_id`)、`name`、`funds`、
`activity`、`created_at`、`public`、`friendly_fire`(=`allow_friendly_fire`)、`owner`(=队长名)、
`owner_uuid`、`notice`、`notice_updated_at`、`member_count`、`operator_count`、`message_count`、
`application_count`、`currency_name`。
成员：`uuid`、`name`、`role`(`OPERATOR`/`MEMBER`)、`operator`(bool)、`online`、`joined_at`(恒为 null 占位)。
申请：`applicant_uuid`(=`uuid`)、`applicant`(=`name`)、`applied_at`。
留言：`sender`(=`sender_name`)、`sender_uuid`、`content`、`time`、`timestamp`。
流水：`type`(存入/取出)、`amount`(=`|change|`)、`note`(=`reason`)、`change`、`balance_before`、`balance_after`、`time`、`timestamp`。

> 网页设计详细稿件见 `docs/team-web-design.md`。


## 领地（module=residence，对接 Zrips/Residence 本体公开接口）

> 只读接口所有登录玩家可用；写接口仅限「领地主人 / 父领地主人」。
> 子领地用 `父领地.子领地` 路径访问（例如 `res1.sub2`）。
> 所有操作在服务器主线程串行执行，写操作按领地加锁，避免网页与游戏内同时操作导致数据异常。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/residences?page=&page_size=&query=&owner=&mine= | 领地列表；`mine=true` 只看自己的 |
| GET | /api/residences/:residence | 领地详情（区域边界/子领地/权限/租售/银行/提示语） |
| GET | /api/residences/:residence/flags | 领地 flag 与全部可用 flag（possible_flags） |
| GET | /api/residences/:residence/players/:player/flags | 某玩家（玩家名或 UUID）在此领地的 flag |
| POST | /api/residences/:residence/flags | 设置领地 flag：`{flag, state:"true"|"false"|"remove"}` |
| POST | /api/residences/:residence/players/:player/flags | 设置玩家 flag：`{flag, state:"true"|"false"|"remove"}` |
| POST | /api/residences/:residence/players/:player/remove | 移除玩家单个 flag：`{flag}` |
| POST | /api/residences/:residence/players/:player/clear | 清空玩家全部 flag |
| POST | /api/residences/:residence/apply-defaults | 重置为默认权限 |
| POST | /api/residences/:residence/message | 设置进出提示语：`{type:"enter"|"leave", message}`（message 为空=清除） |

常见返回字段：`name / owner / owner_uuid / world / areas / subzones / flags / player_flags / trusted_players / enter_message / leave_message / for_sale / sell_price / for_rent / rentable / rented_detail / bank / created_at / size`。
## 工单（module=ticket，数据保存在插件 data/tickets.json）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/tickets | 创建工单：`{subject, content}` |
| GET | /api/tickets | 我的工单列表 |
| GET | /api/tickets/:id | 工单详情（仅本人） |
| POST | /api/tickets/:id/reply | 回复自己的工单：`{content}` |

### 管理（X-Admin-Token）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/admin/tickets | 全部工单 |
| GET | /api/admin/tickets/:id | 工单详情 |
| POST | /api/admin/tickets/:id/reply | 管理员回复：`{content}` |
| POST | /api/admin/tickets/:id/status | 改状态：`{status:"OPEN"|"CLOSED"}` |

> 管理后台按需求只保留工单功能；RBAC/审计/文件管理/备份/商店等不在本平台范围内。
