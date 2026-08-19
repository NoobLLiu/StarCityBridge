# 领地系统网页设计稿件（对接 Zrips/Residence 本体）

> 适用范围：StarCityBridge 网页平台「领地系统」。
> 页面内容设计参考 **ResidenceList**（其 GUI 的列表 / 详情 / 管理 / 权限 / 市场 / 租赁页面）；
> 后端接口完全以 **Residence 插件本体（Zrips/Residence）的公开 API** 为准，
> **不改动 Residence 本体任何代码与接口**，所有对接逻辑都在 StarCityBridge 内完成。
> 权限校验在 StarCityBridge 内实现（与游戏内行为一致），网页端只把「当前登录玩家的 `player_uuid`」随请求带给后端，
> 由后端判断可见性 / 可操作性：**玩家看不到自己无权看的领地，做不了自己无权做的操作**。

---

## 0. 设计原则（与游戏内一致）

1. **可见性即权限**：领地列表/详情只展示「可见」领地——服务器领地、未开启 `hidden` 旗标的领地、本人拥有的领地、管理员可见全部。隐藏领地（`hidden=true`）对非主人不可见。
2. **可操作性即权限**：写操作（旗标、进出提示、重命名、镜像权限、出售/取消出售、出租/取消出租）仅「领地主人 / 父领地主人 / 管理员」可做；转让要求发起者与接收者都在线（Residence 本体硬性要求）；删除要求主人或管理员并二次确认。
3. **单一数据源**：网页操作直接写入 Residence 同一存储（调用 Residence 公开 API），与游戏内操作完全一致，不会产生两份数据。
4. **规则复用**：重命名名称合法性、出售/出租的经济开关、租期上限、领地数量/面积限制、转让须在线等——全部沿用 Residence 本体同一套校验；本模块只做入口与容错，不绕过任何本体检查。
5. **离线容错**：只读接口与「旗标编辑/重命名/镜像/出售挂牌/取消挂牌」允许玩家离线使用；**购买、出租设置、租用、退租、支付租金、转让**这些 Residence API 只接受在线 `Player` 对象，离线时后端返回明确中文提示（"请先登录服务器"），绝不抛错、不崩溃。
6. **不在网页暴露的能力**（与游戏内一致，本迭代不做）：
   - 创建领地：需要游戏内先用木斧选区，网页无法选区；
   - 传送 / 设置传送点：仅对在线玩家有意义；
   - 领地图标 / 昵称 / 描述 / 公开显示 / 评分 / 置顶：这些是 ResidenceList 插件自己的扩展数据（非 Residence 本体），本迭代不实现；列表仅显示 Residence 本体字段。

---

## 1. 页面地图（Sitemap）

```
┌─ 领地系统 ─────────────────────────────────────────────────┐
│ A  领地列表页                                               │
│     ├─ 视角切换：所有领地 / 我的领地（mine=true）           │
│     ├─ 搜索（名称/主人） + 分页                             │
│     └─ 导航入口：领地市场 / 我的租用 / 创建领地（提示需游戏内选区）│
│ B  领地详情页（他人可见领地）                                │
│     ├─ 基本信息：名称/主人/世界/区域数/子领地数/大小/创建时间│
│     ├─ 区域边界（各 area 的 low/high 坐标）                 │
│     ├─ 子领地列表（可点入对应详情）                         │
│     ├─ 受信玩家列表                                         │
│     ├─ 进出提示语                                           │
│     ├─ 租售状态卡（出售价 / 出租租金与租期 / 是否已租出）    │
│     └─ 操作：若本人拥有 → 进入管理页；若在售 → 购买按钮      │
│ C  领地管理页（仅主人/父主人/admin）                        │
│     ├─ C1 基本信息编辑：重命名 / 进出提示语                 │
│     ├─ C2 权限管理：全局权限（分类）/ 玩家权限               │
│     ├─ C3 高级：重置默认权限 / 镜像权限（从源领地复制）      │
│     ├─ C4 市场操作：出售/取消出售 / 出租设置/取消出租/退租/支付租金 │
│     └─ C5 危险操作：转让（双方在线）/ 删除（二次确认）       │
│ D  全局权限页（按分类分组）                                 │
│     ├─ 分类列表 → 分类内 flag 列表                          │
│     └─ 每个 flag：当前值（允许/拒绝/默认）/ 默认值 / 描述    │
│ E  玩家权限页                                               │
│     ├─ 受信玩家列表 → 选中玩家 → 其 flag 列表（同 D 交互）   │
│     └─ 移除玩家全部权限                                     │
│ F  领地市场页（购买/租借）                                  │
│     ├─ 出售中的领地（主人/价格/大小）→ 购买                  │
│     └─ 可租领地（主人/日租金/租期/可续租）→ 租用             │
│ G  我的租用页（我租用的领地 + 到期时间 + 自动续租 + 续租按钮）│
└─────────────────────────────────────────────────────────────┘
```

**未登录/未绑定玩家**：不展示领地入口（需先登录获取玩家 token）。

---

## 2. 权限矩阵

| 功能 | 访客 | 领地主人* | 服务器管理员** |
|---|---|---|---|
| 浏览可见领地列表 / 详情 | ✔（仅可见领地） | ✔ | ✔（全部） |
| 查看隐藏领地（hidden） | ✘ | ✔（自己的） | ✔ |
| 编辑领地 flag / 玩家 flag | ✘ | ✔ | ✔ |
| 重置默认权限 / 镜像权限 | ✘ | ✔（镜像需同时拥有源领地） | ✔ |
| 重命名 / 进出提示语 | ✘ | ✔ | ✔ |
| 出售挂牌 / 取消出售 / 取消出租挂牌 | ✘ | ✔ | ✔ |
| 购买领地 / 租用领地 | ✘ | ✘（不能买/租自己的） | ✔ |
| 出租设置 / 退租（强制）/ 支付租金 | ✘ | ✔（退租可强制） | ✔ |
| 转让领地 | ✘ | ✔（发起者+接收者均须在线） | ✔ |
| 删除领地 | ✘ | ✔（confirm 二次确认） | ✔ |
| 创建领地 | ✘ | ✘（需游戏内选区） | ✘ |
| 传送 / 设置传送点 | ✘ | ✘ | ✘ |

\* 含父领地主人（可管理子领地）。
\*\* 服务器管理员 = `is_op` 玩家（登录令牌 `is_op=true`），通过 portal 路由携带 `admin` 标志生效；本迭代不新增独立管理后台路由（与「管理后台仅保留工单」保持一致）。

---

## 3. 逐页设计

### A 领地列表页

**内容**
- 顶部视角切换：「所有领地」与「我的领地」（`mine=true`）。
- 领地卡片/行：名称、主人、世界、区域数、大小、是否出售/出租（角标）、创建时间。
- 搜索框（按名称/主人包含匹配）+ 分页。
- 导航入口：领地市场、我的租用；「创建领地」按钮点击后提示"需在游戏内用木斧选区后创建"。

**逻辑**
- 数据来源：`GET /api/residences?page=&page_size=&query=&mine=`。
- 后端按可见性过滤：服务器领地 / 非 `hidden` 领地 / 本人拥有 / 管理员全部。
- 点击自己拥有的领地 → 管理页（C）；点击他人领地 → 详情页（B）。
- 空态：无可视领地时显示空提示。

### B 领地详情页（他人可见领地 / 只读）

**内容**
- 基本信息卡：名称、主人（含 UUID）、世界、主区域大小、区域数、子领地数、创建时间、是否服务器领地、是否隐藏。
- 区域边界列表：每个 area 的名称、世界、`low[x,y,z]` / `high[x,y,z]`、大小。
- 子领地列表：名称、主人；点击进入对应子领地详情。
- 受信玩家列表：玩家名 + UUID。
- 进出提示语（enter_message / leave_message）。
- 租售状态卡：
  - 出售中：售价（`for_sale=true` + `sell_price`），显示「购买」按钮（若本人不是主人）。
  - 出租中：日租金、租期、是否可续租；若未租出显示「租用」按钮（若本人不是主人）；若已租出显示租客与到期时间。
- 经济系统状态：`economy_enabled` / `rent_system_enabled`（未启用时隐藏购买/租用按钮并提示）。

**逻辑**
- 数据来源：`GET /api/residences/:residence`。
- 后端返回 `viewable`（当前玩家是否可看）与 `can_manage`（是否可管理）；非可见领地直接 400「领地不存在或无权查看」（与游戏内"看不到隐藏领地"一致）。
- 子领地访问：`父领地.子领地`（如 `res1.sub2`）。
- 网页端只展示坐标，不做传送。

### C 领地管理页（仅主人/父主人/admin）

**入口**：领地详情页中 `can_manage=true` 时显示。

**C1 基本信息编辑**
- 重命名：输入新名称 → `POST /api/residences/:residence/rename`（body `{new_name}`）；失败展示后端中文错误（名称不合法/被占用）。
- 进出提示语：`POST /api/residences/:residence/message`（body `{type:"enter"|"leave", message}`；message 为空=清除）。

**C2 权限管理**
- 「全局权限」→ D 页；「玩家权限」→ E 页。

**C3 高级**
- 重置默认权限：`POST /api/residences/:residence/apply-defaults`（需二次确认交互：Shift+点击 或 弹窗确认）。
- 镜像权限：输入源领地名称 → `POST /api/residences/:residence/mirror`（body `{source}`）；后端校验同时拥有目标与源领地。

**C4 市场操作（出售/出租）**
- 出售：
  - 未出售：输入价格 → `POST /api/residences/:residence/sell`（body `{price}`）。
  - 已出售：显示售价 + 「取消出售」→ `POST /api/residences/:residence/unlist-sell`。
- 出租：
  - 未出租：打开出租设置表单（日租金/租期天数/允许续租/留在市场/自动支付）→ `POST /api/residences/:residence/rent-settings`（body `{cost, days, allow_renewing, stay_in_market, allow_auto_pay}`）——**需要玩家在线**。
  - 已出租未租出：显示租金/租期 + 「取消出租」→ `POST /api/residences/:residence/unlist-rent`（不需要在线）。
  - 已租出：显示租客/到期时间 + 「强制退租」→ `POST /api/residences/:residence/unrent`——**需要玩家在线**。
- 经济系统未启用时，出售/出租按钮置灰并提示。

**C5 危险操作**
- 转让领地：输入目标玩家名 → `POST /api/residences/:residence/transfer`（body `{target}`）——**发起者与接收者都必须在线**（Residence 本体要求），离线返回明确提示。
- 删除领地：弹窗二次确认（输入领地名或勾选确认）→ `POST /api/residences/:residence/delete`（body `{confirm:true, confirm_name?}`）。删除不可逆，成功后返回列表。

### D 全局权限页

**内容**
- 顶部：分类入口（建造与破坏 / 交互与使用 / 物品与掉落 / 移动与传送 / 生物与实体 / 环境与物理 / 战斗与保护 / 视觉效果 / 经济与领地）。
- 进入分类后展示该分类下所有 flag，每个 flag 显示：
  - 名称（中文名 + 原始名）、描述、默认值；
  - 当前状态：`true`=允许（绿）、`false`=拒绝（红）、`null`=未设置（灰/默认）；
  - 可编辑性（`global_editable`，由后端按 Residence 本体 `checkValidFlag` + FlagMode 计算）。

**逻辑**
- 数据来源：`GET /api/residences/:residence/flags`（返回 `categories[]` 分组结构，兼容旧 `flags`/`possible_flags` 字段）。
- 操作：点击「允许」→ `POST .../flags`（body `{flag, state:"true"}`）；「拒绝」→ `state:"false"`；「恢复默认」→ `state:"remove"`。
- 不可编辑的 flag（如当前模式限制 / 战斗保护中的 pvp 变更守卫）前端置灰，后端同样拒绝。

### E 玩家权限页

**内容**
- 受信玩家列表（玩家名 + 头像/首字母 + 移除全部权限按钮）。
- 选中玩家后进入其 flag 列表（交互同 D，但操作接口为玩家 flag）。

**逻辑**
- 列表数据：`GET /api/residences/:residence` 中的 `trusted_players`。
- 单玩家 flag 查询：`GET /api/residences/:residence/players/:player/flags`（`:player` 可为玩家名或 UUID）。
- 设置/移除：`POST .../players/:player/flags`（body `{flag, state:"true"|"false"|"remove"}`）、`POST .../players/:player/remove`（body `{flag}`）。
- 清空全部：`POST .../players/:player/clear`。
- 移除单个 flag / 清空全部 / 设置 flag 均不需要目标玩家在线（Residence 本体 CommandSender 变体支持玩家名/UUID），未注册玩家返回明确提示。

### F 领地市场页

**内容**
- 两个分区：出售中的领地（主人、售价、大小、世界）、可租领地（主人、日租金、租期天数、是否可续租）。
- 经济系统状态提示（`economy_enabled` / `rent_system_enabled`）。

**逻辑**
- 数据来源：`GET /api/residences/market?page=&page_size=`。
- 购买：`POST /api/residences/:residence/buy`——**需要玩家在线**；成功刷新列表，失败展示后端中文错误（余额不足/已达上限/不能买自己的）。
- 租用：`POST /api/residences/:residence/rent`（body `{auto_pay?}`）——**需要玩家在线**。

### G 我的租用页

**内容**
- 我租用的领地列表：领地名称、主人、日租金、租期、租客（本人）、到期时间、自动支付开关。

**逻辑**
- 数据来源：`GET /api/residences/me/rents`。
- 续租/支付租金：`POST /api/residences/:residence/pay-rent`——**需要玩家在线**。
- 退租：`POST /api/residences/:residence/unrent`——**需要玩家在线**。

---

## 4. API 契约（module=residence）

> 统一响应：`{ code, message, data }`（code=0 成功）。调用者身份由登录令牌注入 `player_uuid`；管理员由令牌 `is_op` 注入 `admin=true`。

### 只读（所有登录玩家可用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/residences?page=&page_size=&query=&owner=&mine=` | 领地列表（可见性过滤；`mine=true` 只看自己的） |
| GET | `/api/residences/:residence` | 领地详情（区域/子领地/权限/租售/银行/提示语/可见性/可管理性/传送坐标[在线时]） |
| GET | `/api/residences/:residence/flags` | 全局 flag：`flags`/`possible_flags` + `categories[]` 分类结构 |
| GET | `/api/residences/:residence/players/:player/flags` | 某玩家（玩家名或 UUID）在此领地的 flag |
| GET | `/api/residences/market?page=&page_size=` | 领地市场：出售中 + 可租（未租出），`items[]` + `economy_enabled`/`rent_system_enabled` |
| GET | `/api/residences/me/rents` | 我租用的领地（`rents[]`） |

### 写（不需要玩家在线）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/residences/:residence/flags` | 设置领地 flag：`{flag, state:"true"|"false"|"remove"}` |
| POST | `/api/residences/:residence/players/:player/flags` | 设置玩家 flag：`{flag, state}` |
| POST | `/api/residences/:residence/players/:player/remove` | 移除玩家单个 flag：`{flag}` |
| POST | `/api/residences/:residence/players/:player/clear` | 清空玩家全部 flag |
| POST | `/api/residences/:residence/apply-defaults` | 重置为默认权限 |
| POST | `/api/residences/:residence/message` | 设置进出提示：`{type:"enter"|"leave", message}` |
| POST | `/api/residences/:residence/rename` | 重命名：`{new_name}` |
| POST | `/api/residences/:residence/mirror` | 镜像权限：`{source}`（需同时拥有源领地） |
| POST | `/api/residences/:residence/delete` | 删除领地：`{confirm:true, confirm_name?}`（主人/父主人/admin） |
| POST | `/api/residences/:residence/sell` | 出售挂牌：`{price}` |
| POST | `/api/residences/:residence/unlist-sell` | 取消出售挂牌 |
| POST | `/api/residences/:residence/unlist-rent` | 取消出租挂牌（未租出时） |

### 写（需要玩家在线——Residence API 只接受 Player 对象，离线返回明确提示）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/residences/:residence/buy` | 购买领地 |
| POST | `/api/residences/:residence/rent-settings` | 出租设置：`{cost, days, allow_renewing, stay_in_market, allow_auto_pay}` |
| POST | `/api/residences/:residence/rent` | 租用：`{auto_pay?}` |
| POST | `/api/residences/:residence/unrent` | 退租 / 强制退租（主人/租客/admin） |
| POST | `/api/residences/:residence/pay-rent` | 支付租金（续租，租客/admin） |
| POST | `/api/residences/:residence/transfer` | 转让：`{target}`（发起者+接收者均须在线） |

### 常见返回字段

- 列表行：`name / owner / owner_uuid / world / areas / subzones / size / for_sale / sell_price / for_rent / rented / created_at`
- 详情附加：`subzone / parent / is_server_land / hidden / viewable / can_manage / economy_enabled / rent_system_enabled / teleport{world,x,y,z} / areas[] / subzones_detail[] / flags / player_flags / trusted_players[] / enter_message / leave_message / rentable{...} / rented_detail{...} / bank`
- flags 分类：`categories[]{ key, name, flags[]{ flag, name, desc, default, mode, value(null|true|false), global_editable, player_editable } }`
- 市场行：`residence / name / owner / owner_uuid / world / type("sell"|"rent") / price / size / areas`（rent 额外 `days`/`renewable`）
- 我的租用：`rents[]{ residence / owner / world / cost / days / renter / end_time / auto_pay }`

---

## 5. 前端要点（给前端实现者）

1. **玩家离线限制**：购买/出租/租用/退租/支付租金/转让 接口在玩家离线时返回 `code!=0` + 中文提示（"请先登录服务器"），前端直接展示 message，不要假定成功。
2. **flag 三态**：flag 的 `value` 为 `true`/`false`/`null`（未设置=跟随默认）。操作映射：允许=`true`、拒绝=`false`、恢复默认=`remove`。
3. **分类渲染**：优先用 `categories[]` 分组渲染权限页；`possible_flags` 保留作兜底。
4. **隐藏领地**：列表已由后端过滤；详情接口对不可见领地返回错误，前端展示"领地不存在或无权查看"。
5. **子领地路径**：`父领地.子领地` 作为 `:residence` 路径参数，前端需 URL 编码。
6. **删除/重置权限**：前端必须做二次确认交互（删除还需传 `confirm=true`）。
7. **经济未启用**：`economy_enabled=false` / `rent_system_enabled=false` 时隐藏对应购买/出租按钮并提示。
8. **列表分页**：后端返回 `total / page / page_size / residences[]`（列表）或 `items[]`（市场）。

---

## 6. 与 ResidenceList 的差异说明（为什么网页上没有某些功能）

| ResidenceList 功能 | 网页端 | 原因 |
|---|---|---|
| 领地图标 / 昵称 / 描述 / 公开显示 / 评分 / 置顶 | ✘ | 这些是 ResidenceList 自己存储的扩展数据，非 Residence 本体接口，本迭代不实现 |
| 创建领地 | ✘ | 需要游戏内木斧选区 |
| 传送 / 设置传送点 | ✘（仅显示坐标） | 仅对在线玩家有意义（与 MGTeam 传送同理） |
| 批量选择玩家设权限 | ✘（单玩家设置） | 保持最小实现，可后续扩展 |
| 市场购买/租借 | ✔（需玩家在线） | Residence 交易 API 仅接受在线 Player 对象 |
| 权限 / 市场 / 租赁主体功能 | ✔ | 由 StarCityBridge 直接调用 Residence 本体公开 API |
