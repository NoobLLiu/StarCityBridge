# 团队系统网页设计稿件（对接 MGTeam-JE）

> 适用范围：StarCityBridge 网页平台「团队系统」。
> 本稿件描述网页端团队功能**每一页有什么内容、什么逻辑**（不含 UI 视觉设计），
> 并给出与后端 API 的对应关系。权限校验在 MGTeam-JE 插件导出层完成，
> 网页端只是把「当前登录玩家的 `player_uuid`」随请求带给后端，由插件判断可见性/可操作性，
> 与游戏内行为完全一致：**玩家看不到自己无权看的团队数据，做不了自己无权做的操作**。

---

## 0. 设计原则（与游戏内一致）

1. **可见性即权限**：团队详情/成员/资金/留言仅「本团队成员」可见；申请列表/资金流水仅「团队管理员(operator)」可见；排行榜与搜索仅展示公开团队（私密团队只能按 ID 精确搜索到，但详情同样不可见）。
2. **可操作性即权限**：管理操作（通过申请、任命/降级/移出成员、改名、公告、公开开关、友伤开关、解散、取出资金）仅 operator 可做；普通成员只能查看、存入资金、发留言、退出。
3. **单一数据源**：网页操作直接写入 MGTeam-JE 同一存储，触发与游戏内相同的资金/留言提醒；游戏内与网页同时操作不会产生两份数据。
4. **规则复用**：创建团队（成长等级、名称 2-10 字且唯一、扣除创建费用）、留言冷却与 100 字上限、至少保留 1 名 operator、operator 不能直接退出/被移出、解散需输入团队名确认——全部沿用游戏内同一套规则。
5. **离线容错**：只读接口与「标记已读」允许离线玩家使用；涉及玩家余额/成长等级/在团队内身份的操作要求玩家在线，返回明确中文提示（不会让网页崩溃）。

---

## 1. 页面地图（Sitemap）

```
┌─ 未加入团队 ─────────────────────────────────────────────┐
│  A1 团队总览页（游客态）                                  │
│     ├─ 公开团队排行榜（分页 + 搜索）                     │
│     ├─ 团队搜索结果（ID/名称）                           │
│     ├─ 创建团队（弹窗/独立页）                           │
│     └─ 我的状态卡片：未加入 → 显示"创建团队/申请加入"入口 │
├─ 已加入团队 ─────────────────────────────────────────────┤
│  B  我的团队主页（主页面）                                │
│     ├─ 基本信息卡：名称/ID/队长/成员数/资金/公开/友伤/公告│
│     ├─ 成员列表（operator 在前，在线状态）                │
│     └─ 导航入口：留言板 / 资金 / 管理（仅 operator）      │
│  C  留言板页（查看 + 发布 + 已读标记）                    │
│  D  团队资金页（余额 + 存入[全员] + 取出[operator] + 流水[operator]）│
│  E  管理页（仅 operator）                                 │
│     ├─ E1 成员管理（任命/降级/移出）                      │
│     ├─ E2 申请管理（通过/忽略）                           │
│     ├─ E3 公告编辑                                       │
│     ├─ E4 设置（改名 / 公开开关 / 友伤开关）              │
│     └─ E5 解散确认（输入团队名）                          │
└───────────────────────────────────────────────────────────┘
```

**未登录/未绑定玩家**：不展示团队入口（需先登录获取玩家 token）。

---

## 2. 权限矩阵

| 功能 | 未加入 | 普通成员 | operator | 服务器管理员* |
|---|---|---|---|---|
| 查看公开团队排行/搜索 | ✔ | ✔ | ✔ | ✔ |
| 查看团队详情/成员/资金/留言 | ✘ | ✔ | ✔ | ✔ |
| 查看申请列表/资金流水 | ✘ | ✘ | ✔ | ✔ |
| 创建团队 / 申请加入 | ✔ | ✘ | ✘ | ✘ |
| 存入资金 / 发留言 | ✘ | ✔ | ✔ | ✔ |
| 取出资金 | ✘ | ✘ | ✔ | ✔ |
| 通过/忽略申请、任命/降级/移出成员 | ✘ | ✘ | ✔ | ✔ |
| 改名 / 公告 / 公开开关 / 友伤开关 / 解散 | ✘ | ✘ | ✔ | ✔ |
| 退出团队 | ✘ | ✔ | ✘（需先降级） | ✘ |

\* 服务器管理员（`mgteam.admin`，对应网页 admin 令牌）可以越权查看任意团队；本迭代暂不在网页暴露
管理端团队路由（与「管理后台仅保留工单」一致），仅保留插件内方法供后续扩展。

---

## 3. 逐页设计

### A1 团队总览页（未加入团队 / 游客态）

**内容**
- 我的团队状态卡片：显示"尚未加入任何团队"。
- 公开团队排行榜：每行显示团队名、队长、成员数、资金、成长值、公开标识；支持分页与关键字搜索（按名称/ID 包含匹配）。
- 「创建团队」按钮；「申请加入」入口。

**逻辑**
- 数据来源：`GET /api/team?page=&page_size=&query=`（只返回公开团队）。
- 玩家点击某团队行 → 不进入详情页（非成员无权限）；提供「申请加入」按钮（携带该团队 tid）。
- 搜索：`GET /api/team/search?query=`；私密团队按 ID 精确搜索可搜到并展示基本信息，但详情仍不可见（与游戏内一致）。
- 创建团队校验（后端执行，前端展示错误）：成长等级达标、名称 2-10 字且唯一、余额≥创建费用（当前配置 10000 星光点，可配置）、玩家当前不在任何团队。
- 空态：无公开团队时显示空提示。

### A2 创建团队（弹窗/独立页）

**内容**：名称输入框、创建费用展示、规则提示（2-10 字、唯一、扣费）。

**逻辑**
- 提交 `POST /api/team/create`（body `{name}`）。
- 成功 → 刷新为「我的团队主页」（B）。
- 失败 → 展示后端返回的中文错误（成长等级不足/名称已存在/余额不足/已在团队等）。
- 要求玩家在线（后端会校验），离线时提示"该操作需要玩家在线"。

### B 我的团队主页（主页面）

**内容**
- 顶部信息卡：团队名、团队 ID、队长（owner）、我的身份（OPERATOR/MEMBER）、成员数、团队资金、公开/私密、允许友伤、公告（含未读标记）、创建时间、货币名。
- 成员列表：operator 排在前（带"管理员"标记），其余成员在后；每行显示名称、身份、在线状态。
- 在线队友快捷区（可选）：显示同团队当前在线成员（信息性展示，不含传送，传送已按要求移除）。
- 导航入口：留言板、资金、管理（仅 operator 显示）。

**逻辑**
- 数据来源：`GET /api/team/me`（扁平结构，含 `members` 数组与 `my_role`）。
- 未读提示：配合 `GET /api/team/:tid/message_state` 展示「新留言/新公告」角标。
- 公告展示：公告为纯文本（最多 100 字）；进入页面后调用标记已读接口（见 C）。

### C 留言板页

**内容**：留言列表（发送者、时间、内容，最新在前，分页）+ 发布输入框 + 「标记已读」。

**逻辑**
- 数据来源：`GET /api/team/:tid/messages?page=&page_size=`（默认每页 10，最多 100）。
- 发布：`POST /api/team/:tid/message`（body `{content}`）；限制：非空、≤100 字、冷却（默认 10 分钟，后端返回剩余冷却文案）。
- 已读标记：进入留言板时调用 `POST /api/team/:tid/message/read`（允许离线，标记本人已读）；公告已读 `POST /api/team/:tid/notice/read`。
- 仅团队成员可访问；非成员请求 → 后端返回"您不在此团队中"。

### D 团队资金页

**内容**：当前资金余额（含货币名）、存入表单（全员）、取出表单（仅 operator）、资金流水列表（仅 operator，分页）。

**逻辑**
- 余额：`GET /api/team/:tid/funds`。
- 存入：`POST /api/team/:tid/funds/deposit`（body `{amount}`）；校验金额>0、余额充足；写流水并提醒管理员。
- 取出：`POST /api/team/:tid/funds/withdraw`（body `{amount}`）；仅 operator；校验团队资金充足。
- 流水：`GET /api/team/:tid/logs?page=&page_size=`（默认 50/页，最多 100）；每行：类型（存入/取出）、金额、原因（操作者）、变动前后余额、时间。
- 并发：网页与游戏内同时存取共用同一余额，后端在主线程串行执行并加玩家锁，不会出现负数/重复扣款。

### E 管理页（仅 operator）

#### E1 成员管理
**内容**：成员/管理员列表 + 对每个成员的操作按钮。
**逻辑**
- 数据来源：`GET /api/team/:tid/members`（数组；operator 在前）。
- 任命管理员：`POST /api/team/:tid/member/promote`（body `{target_uuid}`）。
- 降级为成员：`POST /api/team/:tid/member/demote`（body `{target_uuid}`）；至少保留 1 名 operator，否则后端拒绝。
- 移出成员：`POST /api/team/:tid/member/remove`（body `{target_uuid}`）；operator 不能被直接移出（需先降级）；不能移出自己（需先降级再退出）。

#### E2 申请管理
**内容**：待处理申请列表（申请人、申请时间）+ 通过/忽略按钮。
**逻辑**
- 数据来源：`GET /api/team/:tid/applications`（数组；仅 operator 可见）。
- 通过：`POST /api/team/:tid/application/accept`（body `{applicant_uuid}`）；若申请人已加入其它团队则自动移除该申请并提示。
- 忽略：`POST /api/team/:tid/application/reject`（body `{applicant_uuid}`）。

#### E3 公告编辑
**内容**：公告文本域 + 保存按钮。
**逻辑**：`POST /api/team/:tid/notice`（body `{notice}`）；≤100 字，空串=清除；保存后公告更新时间戳刷新（触发成员"新公告"未读）。

#### E4 设置
**内容**：改名输入框、公开/私密开关、允许友伤开关。
**逻辑**
- 改名：`POST /api/team/:tid/rename`（body `{name}`）；2-10 字且唯一。
- 公开：`POST /api/team/:tid/public`（body `{public:true|false}`）。
- 友伤：`POST /api/team/:tid/friendly-fire`（body `{allow:true|false}`）。

#### E5 解散确认
**内容**：危险操作提示 + 团队名确认输入框。
**逻辑**：`POST /api/team/:tid/disband`（body `{confirm_name}`）；必须输入与当前团队名完全一致才可解散；解散后删除团队、留言、资金流水。建议前端二次确认弹窗。

---

## 4. 后端 API 契约摘要（本迭代生效）

> 基地址 `http://<MC服务器>:8083/api`；请求头 `Authorization: Bearer <token>`；响应统一 `{code, message, data}`，`code=0` 成功。

### 只读（自动带 `player_uuid`，权限校验在插件内）

| 方法 | 路径 | data 结构 | 可见范围 |
|---|---|---|---|
| GET | /api/team?page=&page_size=&query= | `{page,page_size,total_pages,total_items,items:[teamView]}` | 公开团队 |
| GET | /api/team/search?query= | `{teams:[teamView],total}` | 全部匹配（含私密按ID） |
| GET | /api/team/me | 扁平 `{in_team:bool, tid,name,my_role,...members:[...]}` | 本人 |
| GET | /api/team/:tid | `teamDetailView`（含 members） | 仅成员 |
| GET | /api/team/:tid/members | **数组** `[memberView]` | 仅成员 |
| GET | /api/team/:tid/applications | **数组** `[applicationView]` | 仅 operator |
| GET | /api/team/:tid/funds | `{tid,team_id,funds,currency_name}` | 仅成员 |
| GET | /api/team/:tid/logs | `{page,...,items:[fundLogView]}` | 仅 operator |
| GET | /api/team/:tid/messages | `{page,...,items:[messageView]}` | 仅成员 |
| GET | /api/team/:tid/message_state | 未读/公告状态 | 仅成员 |
| GET | /api/team/online-teammates | 在线队友（信息性） | 仅成员 |

> `teamView` 字段：`tid`(=`team_id`)、`name`、`funds`、`activity`、`created_at`、`public`、
> `friendly_fire`(=`allow_friendly_fire`)、`owner`(=`owner_name`)、`owner_uuid`、`notice`、
> `notice_updated_at`、`member_count`、`operator_count`、`message_count`、`application_count`、`currency_name`。
> `memberView`：`uuid`、`name`、`role`(`OPERATOR`/`MEMBER`)、`operator`(bool)、`online`、`joined_at`(数据模型未存，恒为 null，前端显示占位)。
> `messageView`：`sender`(=`sender_name`)、`sender_uuid`、`content`、`time`、`timestamp`。
> `fundLogView`：`type`(存入/取出)、`amount`(=`|change|`)、`reason`(=`note`)、`change`、`balance_before`、`balance_after`、`time`、`timestamp`。
> `applicationView`：`applicant`(=`name`)、`applicant_uuid`、`applied_at`。

### 写操作（`player_uuid` 自动注入；需要在线时会返回"该操作需要玩家在线"）

| 方法 | 路径 | Body | 权限 |
|---|---|---|---|
| POST | /api/team/create | `{name}` | 未加入 |
| POST | /api/team/:tid/join | 空 | 未加入 |
| POST | /api/team/:tid/application/accept | `{applicant_uuid}` | operator |
| POST | /api/team/:tid/application/reject | `{applicant_uuid}` | operator |
| POST | /api/team/:tid/member/promote | `{target_uuid}` | operator |
| POST | /api/team/:tid/member/demote | `{target_uuid}` | operator |
| POST | /api/team/:tid/member/remove | `{target_uuid}` | operator |
| POST | /api/team/quit | 空 | 普通成员 |
| POST | /api/team/:tid/rename | `{name}` | operator |
| POST | /api/team/:tid/notice | `{notice}` | operator |
| POST | /api/team/:tid/public | `{public:bool}` | operator |
| POST | /api/team/:tid/friendly-fire | `{allow:bool}` | operator |
| POST | /api/team/:tid/disband | `{confirm_name}` | operator |
| POST | /api/team/:tid/funds/deposit | `{amount}` | 成员 |
| POST | /api/team/:tid/funds/withdraw | `{amount}` | operator |
| POST | /api/team/:tid/message | `{content}` | 成员 |
| POST | /api/team/:tid/message/read | 空 | 成员（允许离线） |
| POST | /api/team/:tid/notice/read | 空 | 成员（允许离线） |

> 注：`message/read` 与 `notice/read` 两个已读接口为本次新增的路由（对应已存在的 `mark_messages_read` / `mark_notice_read` 动作）。

---

## 5. 与游戏内一致的关键规则清单（后端已实现，前端提示文案可复用）

- 创建团队：成长等级 ≥ 要求；名称 2-10 字且唯一；扣除创建费用（当前配置 10000 星光点，可配置）；创建者自动成为 operator。
- 申请加入：先清除玩家在所有团队的历史申请再入队；已在团队不能申请。
- 权限：`operator` = 队长/管理员；普通成员无管理权；至少保留 1 名 operator。
- operator 不能直接退出或直接移出（需先降级）；不能通过退出/移出清空最后一名 operator。
- 解散：必须输入与团队名完全一致的确认名。
- 留言：非空、≤100 字、冷却（默认 10 分钟）。
- 资金：存入全体成员、取出仅 operator；金额必须为正整数。
- 只读可见性：详情/成员/资金/留言=成员；申请/流水=operator；排行=公开团队。

---

## 6. 前端数据结构要点（与后端对齐后）

1. `GET /api/team/:tid/members`、`GET /api/team/:tid/applications` 的 `data` 是**数组**（不再是 `{ok,data:{...}}` 包裹对象）。
2. `GET /api/team/me` 是**扁平对象**：`{in_team:false}` 或 `{in_team:true, tid, name, my_role, ...}`；`my_role` 取值 `OPERATOR` / `MEMBER`。
3. 字段已对齐：用 `tid`/`friendly_fire`/`owner`/`sender`/`applicant`/`type`/`note`/`amount`/`my_role`；旧字段 `team_id`/`allow_friendly_fire`/`sender_name`/`applicant_uuid` 等同时保留，前端可任选一套。
4. 权限错误：接口返回 `code!=0` 且 `message` 为中文（如"您不在此团队中"、"需要管理员权限"、"该操作需要玩家在线"），前端直接 toast 展示即可，不需要本地猜权限。

---
