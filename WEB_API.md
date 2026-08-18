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

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/team?page=&page_size=&query= | 公开团队列表 |
| GET | /api/team/me | 我的团队 |
| GET | /api/team/search?query= | 团队搜索 |
| GET | /api/team/:tid | 团队详情 |
| GET | /api/team/:tid/members | 成员列表 |
| GET | /api/team/:tid/applications | 待处理申请 |
| GET | /api/team/:tid/funds | 团队资金 |
| GET | /api/team/:tid/logs?page=&page_size= | 资金流水 |
| GET | /api/team/:tid/messages?page=&page_size= | 团队留言 |
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
| POST | /api/team/:tid/disband | `{confirm_name}` |
| POST | /api/team/:tid/funds/deposit | `{amount}` |
| POST | /api/team/:tid/funds/withdraw | `{amount, admin?:bool}` |
| POST | /api/team/:tid/message | `{content}` |


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
## 管理（需要 X-Admin-Token）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/admin/team/all?page=&page_size= | 全部团队（含私密） |
| POST | /api/admin/team/sync-names | 同步团队成员/申请/队伍锚点名字 |
| POST | /api/admin/team/reload | 重载团队配置 |
| POST | /api/admin/team/:tid/disband | `{confirm_name}` |
| GET | /api/admin/market/stats?days= | 市场行情统计 |
| POST | /api/admin/market/:item_id/suspend | `{suspend:bool}` 停牌/复牌 |
| POST | /api/admin/market/tax | `{percent}` |
| POST | /api/admin/market/announcement | `{action, id?, content}` |

> 管理后台接口中：工单功能已在本版本内置；RBAC/审计/文件管理/备份/商店等其余管理后台接口仍在后续迭代分批并入。
