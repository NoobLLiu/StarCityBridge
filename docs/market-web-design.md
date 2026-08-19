# 市场系统网页设计稿件（对接 StockExchange）

> 适用范围：StarCityBridge 网页平台「市场系统」。
> 本稿件描述网页端市场功能**每一页有什么内容、什么逻辑**（不含 UI 视觉设计），
> 并给出与后端 API 的对应关系。权限校验与业务规则在 StockExchange 的
> `WebMarketManager` 导出层完成，网页端只把「当前登录玩家的 `player_uuid`」随请求带给后端。

---

## 0. 设计原则（与游戏内一致）

1. **网页资产走「个人仓库通道」**：网页下单/撤单/成交一律使用玩家的货币仓库与物品仓库
   （`webPlaceBuy/webPlaceSell` 等），游戏内操作不触碰仓库（除显式存取），两侧永不混用在线背包。
2. **规则复用**：成长等级、数量上限、价格区间/步进、停牌、持仓/余额、自成交、托管一致性、
   每日新增商品上限、税率——全部沿用游戏内同一套校验，错误直接返回中文文案。
3. **可见性即权限**：商品目录/详情/盘口/市场信息对**所有登录玩家公开**；「我的挂单/成交/仓库/余额」
   仅本人可见（后端用 token 里的 `player_uuid` 取数，天然隔离）；管理操作（停牌/税率/公告/重载/重连）
   仅服务器管理员（`admin` 标志，对应游戏内管理员权限）可做。
4. **余额展示**：通过 Vault（经济核心，底层 XConomy）读取玩家余额，在仓库/兑换等页面展示；
   经济不可用时返回 `economy_available=false` 且 `balance=null`，不报错。
5. **离线容错**：网页下单/撤单/市价/兑换/货币存取等走仓库通道，**无需玩家在线**；
   仅「手持物品存入」「提取到游戏背包/余额」需要玩家在线，后端返回明确中文提示。
6. **并发安全**：写操作按玩家 UUID 加可重入锁并强制回服务器主线程串行执行，网页与游戏内
   同时操作不会交叉移动资产。

---

## 1. 页面地图（Sitemap）

```
市场总入口（所有登录玩家可见）
│
├─ A 行情/商品列表页
│   ├─ 出售视角 / 求购视角 切换
│   ├─ 搜索（名称/ID/材料 模糊匹配）
│   ├─ 分页商品表：商品名、最低售价、最高求购、成交量
│   ├─ 点击行 → 选中并加载盘口
│   └─ 工具：我要挂单、钻石兑换、导出 CSV
│
├─ B 品种详情 / 盘口
│   ├─ 基本信息（名称/材质/创建者/涨跌幅/停牌）
│   ├─ 买盘 buys[] / 卖盘 sells[]（价格-数量聚合档位 + 原始挂单）
│   └─ 快捷交易：市价买 / 市价卖 / 快速上架
│
├─ C 挂单流程（弹窗）
│   ├─ 我要挂单：类型（买/卖）、品种、单价、数量、（卖单可选具体物品 base64）
│   └─ 校验：价格区间/步进、数量上限、停牌、余额/库存
│
├─ D 我的交易
│   ├─ 我的挂单（数组）：撤销
│   ├─ 我的成交（分页）：买入/卖出、数量、单价、总额、手续费、时间
│   └─ 我的仓库：货币余额 + 物品列表（存仓/取仓）
│
├─ E 货币兑换页（弹窗）
│   ├─ 钻石 -> 星光点（d2m）：显示到手（扣税）
│   └─ 星光点 -> 钻石（m2d）：显示花费（含税）
│
└─ F 余额展示（D/E 页顶部）
    └─ 我的经济余额（Vault/XConomy）+ 仓库星光点
```

---

## 2. 权限矩阵

| 功能 | 任意登录玩家 | 仅本人 | 服务器管理员* |
|---|---|---|---|
| 浏览商品列表 / 详情 / 盘口 / 市场信息 / 公告 | ✔ | | |
| 我的挂单 / 成交 / 仓库 / 余额 | | ✔（按 token player_uuid） | |
| 挂单 / 市价交易 / 快速上架 / 一键供货 / 直接交易 | ✔（按游戏规则校验） | | |
| 撤单 | ✔（本人订单） | | ✔（可撤他人订单） |
| 仓库存取 / 钻石兑换 | ✔（存取到背包需在线） | | |
| 停牌 / 设置税率 / 公告管理 / 重载 / 重连数据库 | | | ✔ |

\* 管理员接口当前**未在网页暴露 HTTP 路由**（与「管理后台仅保留工单」一致），
仅在 StockExchange 导出层保留 `admin` 标志守卫，供后续扩展。

---

## 3. 逐页设计

### A 行情/商品列表页

**内容**
- 顶部工具条：搜索框、出售视角/求购视角切换、「钻石兑换」「我要挂单」按钮。
- 市场公告卡片（`marketInfo` 的 `notice`/`announcement`）。
- 商品表：商品名、最低售价、最高求购、成交量、操作（市价）。支持分页（默认 35/页，最大 100）。

**逻辑**
- 数据来源：`GET /api/market/items?buy_page=&query=&page=&page_size=`（响应含 `items` 数组与 `total`/`total_pages`）。
- 出售视角显示最低售价、求购视角显示最高求购（与游戏内 tab 一致）；停牌品种保留展示并标注。
- 搜索：`query` 对 名称/ID/材质 模糊匹配（复用游戏内 `MarketListingSearch` 规则）。
- 点击行：加载盘口（B）并允许快捷市价交易。
- 空态/加载/错误均有状态提示；导出 CSV 可选。

### B 品种详情 / 盘口

**内容**
- 品种信息：名称、材质、创建者、今日高低/成交量、7/30 日涨跌幅、停牌状态。
- 盘口：买盘（价高优先）与卖盘（价低优先）各最多 5 档聚合（`price`+`quantity`），并附原始挂单明细。
- 快捷操作：市价买入（按最低卖价）、市价卖出（按最新成交价）、快速上架（仓库同品种全部按最低卖价挂出）、
  求购视角下的一键供货（按求购价从高到低分配，显示预计成交额/扣税/到账）。

**逻辑**
- 数据来源：`GET /api/market/items/:item_id`（详情+挂单列表+供货计划）与
  `GET /api/market/orderbook/:item_id`（盘口）。
- 详情中的挂单标注「是否本人」（`own`），本人卖单可部分取回/整格取回；他人卖单可点击买入。
- 停牌品种禁止市价交易（后端拦截）。

### C 挂单流程（弹窗）

**内容**：类型（买入=求购 / 卖出=上架）、品种、单价、数量；卖出时可选指定具体物品（base64）。

**逻辑**
- 提交 `POST /api/market/order`，body `{type:"buy"|"sell", item_id, price, quantity?, item_base64?}`。
- 后端复用游戏内校验：价格区间/步进、数量上限、停牌、余额（货币仓库）/库存（物品仓库）、
  每日新增上限、自成交规避；失败返回中文原因。
- 挂单/成交使用**仓库资产**，无需玩家在线。

### D 我的交易

#### D1 我的挂单
- 数据来源：`GET /api/market/me/orders`（**数组**）。
- 每行：方向（买入/卖出）、品种、单价、数量、剩余、状态、创建时间；可撤销
  （`POST /api/market/order/:order_id/cancel`），撤销后资产退回仓库。

#### D2 我的成交
- 数据来源：`GET /api/market/me/trades?page=&size=`（分页，含 `items`/`total`）。
- 每行：方向（BUYER/SELLER）、品种、单价、数量、总额、我的手续费、成交时间。

#### D3 我的仓库
- 数据来源：`GET /api/market/me/warehouse`。
- 顶部：**我的经济余额**（Vault/XConomy，`balance`+`currency_name`）、仓库星光点（`money`）。
- 物品列表：物品名、数量（每行含 `item_id`/`name`/`quantity`）。
- 操作：存入（`money` 存入星光点 / `hand` 手持物品，后者需在线）、取出
  （`all` 一键提取 / `money` 提取星光点 / `item` 提取指定物品，均需在线）。

### E 货币兑换页（弹窗）

**内容**：兑换方向（d2m / m2d）、当前汇率、税率、预计到手/花费。

**逻辑**
- 数据来源：`GET /api/market/info`（`diamond_to_money`、`diamond_exchange_tax/received/cost`）。
- d2m：`POST /api/market/exchange` body `{type:"d2m"}`（仓库钻石 -> 余额，扣税）。
- m2d：`POST /api/market/exchange` body `{type:"m2d"}`（余额 -> 仓库钻石，含税）。
- 需要成长等级达标；失败返回中文原因（余额不足/仓库无钻石等）。

### F 余额展示

- 在「我的仓库」与「钻石兑换」页顶部展示玩家经济余额与仓库星光点。
- 来源：`GET /api/market/me/balance`（轻量）或 `GET /api/market/me/warehouse`（含余额）。
- 经济不可用时显示 `economy_available=false`，余额位置显示占位（如「—」），不阻塞其它功能。

---

## 4. 后端 API 契约摘要（本迭代生效）

> 基地址 `http://<MC服务器>:8083/api`；请求头 `Authorization: Bearer <token>`；响应统一 `{code, message, data}`，`code=0` 成功。

### 只读

| 方法 | 路径 | data 结构 |
|---|---|---|
| GET | /api/market/items?buy_page=&query=&page=&page_size= | `{items:[itemView], page, page_size, total, total_pages, total_items, buy_page, query}` |
| GET | /api/market/items/:item_id?buy_page=&page=&page_size= | `{item, status, listing:[orderView], supply_plan?, is_special_category, can_quick_sell, can_supply, can_place_buy, last_price, change_7d_percent, change_30d_percent}` |
| GET | /api/market/orderbook/:item_id | `{item_id, buys:[{price,quantity}], sells:[{price,quantity}], bids, asks, bids_raw, asks_raw, last_price}` |
| GET | /api/market/info | `{currency_name, tax_rate(=tax_rate_percent), diamond_to_money, diamond_exchange_*, price_limit_enabled, limit_up/down_percent, max_order_quantity, price_tick, min/max_price, order_expire_days, announcements, notice, announcement}` |
| GET | /api/market/me/orders | **数组** `[orderView]` |
| GET | /api/market/me/trades?page=&size= | `{items:[tradeView], trades, page, page_size, size, total, total_pages}` |
| GET | /api/market/me/warehouse | `{items:[{item_base64, item_id, quantity, display_name, name, material}], money(=money_balance), balance, currency_name, economy_available, hint}` |
| GET | /api/market/me/balance | `{uuid, balance, currency_name, economy_available, warehouse_money}` |

> `itemView` 关键字段：`item_id`(=`id` 的字符串)、`name`(=`display_name`/`item_name`)、
> `lowest_sell_price`、`highest_buy_price`、`volume`(=`volume_today`)、`suspended`、`active_stock`、
> `change_7d_percent`、`change_30d_percent`、`material`、`created_by`、`created_at`。
> `orderView`：`order_id`(=`id`)、`type`(buy/sell)、`item_id`、`name`(=`item_name`)、`price`、`quantity`、
> `filled_qty`、`remaining_qty`、`status`、`created_at`、`player_name`。
> `tradeView`：`trade_id`(=`id`)、`type`(buy/sell)、`item_id`、`name`、`price`、`quantity`、`total_amount`、
> `fee`(本人手续费)、`time`(=`traded_at`)、`role`(BUYER/SELLER)。

### 写操作

| 方法 | 路径 | Body | 说明 |
|---|---|---|---|
| POST | /api/market/order | `{type:"buy"|"sell", item_id, price, quantity?, item_base64?}` | 挂买单/卖单（走仓库） |
| POST | /api/market/order/:order_id/cancel | `{admin?:bool}` | 撤单（本人；admin 可撤他人） |
| POST | /api/market/trade | `{type:"market_buy"|"market_sell"|"quick_sell", item_id, quantity?}` | 市价交易/快速上架 |
| POST | /api/market/warehouse/deposit | `{type:"money", amount}` 或 `{type:"hand", quantity?}` | 存入星光点/手持物品（hand 需在线） |
| POST | /api/market/warehouse/withdraw | `{type:"all"|"money"|"item", amount?, item_base64?}` | 提取（需在线） |
| POST | /api/market/exchange | `{type:"d2m"|"m2d"}` | 钻石<->星光点兑换 |

> 管理动作（`admin_suspend`/`admin_set_tax`/`admin_announcement`/`admin_reload`/`admin_reconnect`）
> 已加 `admin` 标志守卫，但本迭代**未暴露 HTTP 路由**，仅供后续扩展。

---

## 5. 与游戏内一致的关键规则清单

- 挂买单从**货币仓库**扣款（含交易税），未成交部分退回仓库；挂卖单从**物品仓库**扣货入托管。
- 价格必须在 `min_price`~`max_price` 且符合 `price_tick` 步进；数量 ≤ `max_order_quantity`。
- 停牌品种禁止下单/市价交易。
- 市价买入按最低卖价、市价卖出按最新成交价、快速上架按最低卖价。
- 一键供货只匹配其他玩家的求购单，按求购价从高到低分配，展示预计成交额/扣税/到账。
- 成长等级不达标时禁止兑换/部分市场功能。
- 撤单/成交/部分取回均退回仓库；管理员可撤他人订单。
- 每日新增商品上限（非管理员）由 `registerCatalogItem(admin=false)` 拦截。

---

## 6. 前端数据结构要点（与后端对齐后）

1. `GET /api/market/me/orders` 的 `data` 是**数组**（不再是 `{orders:[...]}`）。
2. 字段用新名：`item_id`、`name`、`volume`、`order_id`、`type`(buy/sell)、`trade_id`、
   `fee`、`time`、`money`、`balance`、`tax_rate`、`notice`；旧字段同时保留。
3. `myTrades` 返回 `{items, total, page, page_size}`，可直接喂给分页组件。
4. 权限/规则错误：接口返回 `code!=0` 与中文 message（如"该操作需要玩家在线"、"余额不足"、
   "该品种已停牌"），前端 toast 展示即可。
5. 余额展示：`balance` 可能为 `null`（`economy_available=false`），显示占位即可。

---
