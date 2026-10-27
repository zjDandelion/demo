# AI 智能下单（A：自然语言生成购物车草稿，并入 B：语义匹配）设计方案

> 状态：已按第二轮评审修订并完成实施（M1–M4，2026-10-27）
> 日期：2026-10-27（第一版：Python 独立服务；第二版：修正验收数据、迁移回填、错误契约、降级语义、合并策略、分类命中）
> 前置阅读：`docs/microservices-design.md`、`docs/known-issues.md`

## 0. 第二轮评审处理

| # | 评审问题 | 处理 | 章节 |
|---|---|---|---|
| 1 | 验收例子（拿铁/可颂）与种子数据（8 个外设）不一致，M1 演示不成立 | **保留现有种子商品**，验收文案改为外设场景；理由见 §1.1 | §1.1 |
| 2 | V2 只加可空列，历史数据 category/tags 永远为 NULL | 增加 **V3 幂等回填**（按商品名、`COALESCE` 不覆盖人工修改）；新库由种子数据直接写入 | §6 |
| 3 | 错误响应形状与 `ErrorResponse` 不符（没有 `code`） | 沿用 `{message, fieldErrors?}`，**不新增 `code`**；429 走 `BusinessException(TOO_MANY_REQUESTS)` | §4.1 |
| 4 | Python 失败语义未定义 | Python 归一为 `200 + status=DEGRADED + reason`；Java 再把传输层失败映射到 5 种 `degradedReason`，浏览器永不收到 5xx | §4.2、§5.4 |
| 5 | 规则层与模型层合并策略缺失 | **规则命中优先，模型只补 unmatched 子句**；数量不跨源相加；`excludeProductIds` 双向约束 | §5.3 |
| 6 | 分类命中直接进 items（"来点咖啡"会加整个分类） | 分类/多候选命中**只出 `questions[].candidates`**，永不进 `items` | §5.1 |

实现建议的落点：SDK `max_retries=0` 与超时（§5.2）、`hmac.compare_digest`（§8）、生产关闭 `/docs`（§8）、非 root 容器（§8）、请求体上限（§8）、日志不记原文（§8）、目录阈值降到 100 + 预筛 Top 50 + 60s 缓存（§7.1、§9）、置信度改由 Java 计算（§4.1、§5.3）、契约 fixture 双端断言（§10.3）、限流单实例说明（§7.1）、compose 的 `expose`/健康检查/内存上限（§7.3）、`.gitignore`/`.dockerignore`（§7.5）、`degradedReason` 区分禁用与失败（§4.1）、AGENTS.md 的本地数据库/端口/datasource 描述纠偏（本轮已改，模块清单留给 M4）。

## 1. 目标与非目标

### 1.1 目标

用户用一句自然语言描述想要的东西，系统解析成商品与数量，**生成可编辑的购物车草稿**，用户在既有结算页确认后下单。

**验收场景（与 `DataInitializer` 的 8 个外设 SKU 对齐）**

| # | 输入 | 期望 |
|---|---|---|
| A | `帮我拿两个机械键盘，再加一张升降桌` | `机械键盘 K87 ×2`（`source=RULE`），`升降桌` 进 `unmatched`（"暂无此商品"） |
| B | `来点外设` | 不进 items；返回 1 个问题 + 4 个候选（机械键盘、无线鼠标、扩展坞、鼠标垫） |
| C | `来两个限量鼠标垫` | `限量鼠标垫 ×2`，`stockShortage=true`（库存 1），仍可加入购物车、由结算时扣减兜底 |

> 为什么改验收文案而不是换种子数据：种子只在 `products` 为空时执行，已部署环境的 PG 卷里仍是这 8 个商品；改种子只会让"新库"和"老库"内容分叉。保持目录不变，用 V3 回填标签，两类环境行为一致。

### 1.2 非目标（本期明确不做）

- **不做 AI 自动下单、自动支付**：写入路径必须由用户点击确认触发。
- **不做多轮对话**：一次请求一次解析，歧义以 `questions` 返回，不追问。
- **不做个性化推荐**：不读历史订单，不做用户画像。
- **不引入向量库 / embedding 检索**：目录整份放入提示词；超过 100 SKU 时先规则预筛，见 §9。
- **不改支付链路**：AI 相关代码不依赖 `na-payment-service`，也无法触达。

### 1.3 为什么"草稿"而不是"订单"

现有系统的安全性建立在三道闸门上：金额由服务端按库内价格重算、库存用条件 UPDATE 扣减、支付台账做幂等与补偿。AI 只负责把自然语言转成 `{productId, quantity}`，输出一律经过 Java 侧校验后落成草稿；即使模型输出错误，最坏结果是"草稿不准"，而不是"下错单"。

## 2. 服务拓扑与落地位置

### 2.1 为什么是独立 Python 服务

- 本机业务栈是 Java 8 / Spring Boot 2.7。**Spring AI 2.x 要求 Java 17 + Boot 4**，LangChain4j 官方最低 JDK 17 且已无 Java 8 维护路径——Java 侧接 AI 只能裸写 HTTP，每加一个能力都要自研。
- 后续若做 RAG、多轮、工具调用、离线评估、模型 A/B，Python 生态（openai SDK、pydantic、pytest、notebook）是现成的。
- 形态与支付服务一致：**只在内网、不映射宿主端口、内部 token、Java 是唯一对外入口**。

### 2.2 拓扑

```
浏览器 ──> na-order-service (Java, 8081→8080)
             │  ├─ 会话鉴权、限流、规则层匹配
             │  ├─ 严格校验（productId 白名单、数量、价格取库）
             │  ├─ 订单写入：POST /api/orders（现有路径，未改动）
             │  └─ 出站调用（连接 2s / 读 4.5s，失败降级）
             ▼
        na-assistant-service (Python/FastAPI, 仅 compose 内网 :8092)
             └─ 提示词组装 + OpenAI 兼容云 API
             ▼
        模型供应商（HTTPS，出网）
```

`na-order-service` 对 `na-assistant-service` **不做健康强依赖**：`docker-compose.yml` 里不加 `depends_on` 健康门控，Python 服务没起来时订单服务照常启动，AI 请求自动降级。

### 2.3 目录

Java 侧（仍在订单服务内，不新增微服务模块）：

```
na-order-service/src/main/java/com/example/na/order/assistant/
├── api/            AssistantPort（对外唯一入口）
├── domain/         DraftItem、DraftQuestion、DraftResult、DegradedReason
├── service/        CatalogMatcher（规则层）、AssistantDraftService（编排与合并）、CatalogSnapshotCache
├── client/         AssistantClient 接口、HttpAssistantClient、ParseResult
├── config/         AssistantProperties、UserRateLimiter
└── web/            AssistantController、DraftRequest、DraftResponse
```

Python 侧（新目录，**不是 Maven 模块**，不被 `mvnw` 构建）：

```
na-assistant-service/
├── app/
│   ├── main.py        FastAPI 应用、路由级鉴权依赖、请求体上限中间件
│   ├── auth.py        内部 token 校验（hmac.compare_digest）
│   ├── schemas.py     pydantic 请求/响应模型
│   ├── prompt.py      提示词模板与目录渲染
│   ├── llm.py         OpenAI 兼容客户端（max_retries=0）
│   └── settings.py    环境变量配置
├── tests/             pytest（含假模型客户端）
├── Dockerfile         python:3.11-slim + 非 root 用户
├── requirements.txt
└── README.md
```

> AGENTS.md 的"三个 Maven 模块"在 M4 改为"三个 Java 模块 + 一个 Python 服务"；其"本地 H2 文件库"等过时描述在 M1 一并纠正。

## 3. 交互流程

```
浏览器                        订单服务(Java)                      assistant(Python)          模型
  │ POST /api/assistant/drafts    │                                    │                     │
  ├──────────────────────────────>│ 1. 文本校验（去空白 1–300 字符）     │                     │
  │                               │ 2. 限流（10 次/分钟/用户）           │                     │
  │                               │ 3. 规则层匹配（本地、无网络）         │                     │
  │                               │ 4. 存在 unmatched 且开关打开 ───────>│ 5. 组装提示词 ──────>│
  │                               │                                      │<────────────────────┤
  │                               │<─────────────────────────────────────┤ 6. pydantic 校验     │
  │                               │ 7. 合并 + 二次校验（白名单/数量/库存/价格取库）             │
  │<──────────────────────────────┤ 200 { items, unmatched, questions, degraded, degradedReason }
  │                                                                                            │
  │ 用户点「加入购物车」→ 写入 localStorage 购物车 → 跳 /checkout → 现有 POST /api/orders        │
```

- **规则全覆盖时不调用 Python**（快速路径）：所有子句都被名称/别名/标签解析成 item，且没有 `unmatched` 子句。
- 只有分类/多候选产生 `questions` 不算 unmatched，也不触发模型——歧义交给用户点选。
- **确认动作就是现有的"加入购物车 + 结算页提交"**：不新增 `confirm` 接口、不新增订单写入路径、不加数据库表。

## 4. 接口契约

### 4.1 对外：`POST /api/assistant/drafts`

需登录（会话 Cookie，沿用现有安全链）。

请求：

```json
{ "text": "帮我拿两个机械键盘，再加一张升降桌" }
```

校验：去首尾空白后 1–300 字符，违规返回 `400 {"message":"..."}`。

响应 `200`：

```json
{
  "draftId": "drf_5f2c9a1b",
  "items": [
    {
      "productId": 1,
      "name": "机械键盘 K87",
      "quantity": 2,
      "unitPrice": 299.00,
      "subtotal": 598.00,
      "stockShortage": false,
      "source": "RULE",
      "score": 0.9
    }
  ],
  "unmatched": [{ "text": "升降桌", "reason": "暂无此商品" }],
  "questions": [],
  "degraded": false,
  "confidence": 0.9
}
```

分类/多候选时的问题结构：

```json
"questions": [
  {
    "text": "「外设」包含多个商品，请选择",
    "candidates": [
      { "productId": 1, "name": "机械键盘 K87", "price": 299.00, "stock": 25 },
      { "productId": 2, "name": "无线鼠标 M330", "price": 129.00, "stock": 40 }
    ]
  }
]
```

| 字段 | 说明 |
|---|---|
| `draftId` | `drf_` + 8 位十六进制；同时作为内部请求的 `requestId`，仅用于前端防重复提交与日志关联；服务端不存储 |
| `items` | 已通过 Java 侧校验的条目；`unitPrice`/`subtotal` 来自数据库，不取模型输出 |
| `items[].source` | `RULE`（规则层）或 `MODEL`（模型补充） |
| `items[].score` | 来源对应的确定性：名称/别名命中 `0.9`、标签命中 `0.7`、模型补充 `0.6`；**不使用模型自报置信度** |
| `unmatched` | 识别出意图但目录里没有的词；模型正常时取模型结果，降级时取规则层结果（§5.3） |
| `questions` | 歧义提示；`candidates` 可空（纯文本问题），最多 5 条、每条 ≤100 字 |
| `degraded` | `true` 表示期望调用模型但未成功，或模型被关闭 |
| `degradedReason` | 仅在 `degraded=true` 时出现（`@JsonInclude(NON_NULL)`），取值见 §5.4 |
| `confidence` | Java 计算：`items` 中最小 `score`，无 items 为 `0`；前端低于 `0.7` 时标黄提示"请核对" |

错误响应沿用现有 `ErrorResponse` 形状，**没有 `code` 字段**（401 由安全链直接写 `{"message":...}`）：

| 状态 | 触发 | 响应体 |
|---|---|---|
| 400 | 文本为空或超 300 字符 | `{"message":"请输入 1–300 字的商品描述"}` |
| 401 | 未登录/会话过期 | `{"message":"未登录或会话已过期"}`（安全链） |
| 429 | 超过 10 次/分钟/用户 | `{"message":"请求过于频繁，请稍后再试"}`（`BusinessException` 携带 `TOO_MANY_REQUESTS`，复用 `handleBusiness`） |
| 500 | 仅规则层自身异常（代码缺陷） | `{"message":"服务器内部错误"}` |

**AI 链路任何外部失败都不产生 4xx/5xx，一律 200 + `degraded=true` + 规则结果。**

### 4.2 内部：`POST /internal/v1/assistant/parse`（Java → Python）

请求头：`X-Service-Token`、`Content-Type: application/json`。

```json
{
  "requestId": "drf_5f2c9a1b",
  "userId": 1,
  "text": "帮我拿两个机械键盘，再加一张升降桌",
  "excludeProductIds": [1],
  "catalog": [
    {
      "id": 1,
      "name": "机械键盘 K87",
      "description": "87 键热插拔机械键盘，白色背光",
      "category": "外设",
      "tags": "键盘,机械键盘,keyboard,输入设备",
      "price": 299.00,
      "stock": 25
    }
  ]
}
```

- `excludeProductIds`：规则层**已解析成 item** 或**已成问题候选**的商品，模型不得再输出；Java 侧仍会二次丢弃（防御）。
- `catalog`：≤100 条，按 `id` 升序，`description` 由 Java 截断到 80 字；Python 不连库、不查用户、不调订单服务。

正常响应 `200`：

```json
{
  "status": "OK",
  "items": [{ "productId": 6, "quantity": 1 }],
  "unmatched": [{ "text": "升降桌", "reason": "暂无此商品" }],
  "questions": [],
  "usage": { "promptTokens": 620, "completionTokens": 48, "latencyMs": 870 }
}
```

降级响应 `200`（模型超时/供应商报错/输出不合法/自身未配置）：

```json
{
  "status": "DEGRADED",
  "reason": "TIMEOUT",
  "items": [],
  "unmatched": [],
  "questions": [],
  "usage": { "promptTokens": 0, "completionTokens": 0, "latencyMs": 4000 }
}
```

`reason` 取值：`DISABLED`（缺 api-key/base-url/model）、`TIMEOUT`、`UPSTREAM_ERROR`、`INVALID_OUTPUT`。

HTTP 状态约定：

| 状态 | 含义 | Java 处理 |
|---|---|---|
| 200 | 正常或降级（看 `status`） | 正常合并 / 透传 reason |
| 400 | 请求结构不合契约（Java 侧 bug） | `degradedReason=UPSTREAM_ERROR`，记 ERROR |
| 401 | token 不匹配（配置错误） | `degradedReason=UPSTREAM_ERROR`，记 ERROR |
| 413 | 请求体超 256KB | `degradedReason=UPSTREAM_ERROR` |
| 5xx | 未预料的异常 | `degradedReason=UPSTREAM_ERROR` |

Python 不返回 5xx 是设计目标；即便返回，Java 也按降级处理。

### 4.3 为什么不给草稿加持久化

草稿生命周期只有一次点击。落库需要新增表、过期清理、归属校验，收益仅是刷新可恢复——MVP 不值得。需要审计时再加 `assistant_draft` 表，接口形状不变。因此响应里也不放无法兑现的 `expiresAt`。

## 5. 解析策略：Java 规则层 + Python 模型层

### 5.1 Java 规则层（永远可用，零成本，无网络）

1. **归一化**：去首尾空白、全角转半角、小写、压缩连续空白。
2. **分句**：按 `，,。；;、!?！？换行` 以及连接词 `以及/还有/另外/再/顺便/加上/和/跟` 切分。
3. **名称/别名匹配**：以商品名 + 标签（标签即别名，含中英文）建词典，规整后按长度**降序**匹配；被更长命中完全包住的关键词丢弃。命中唯一商品 → item，`score=0.9`（名称）或 `0.7`（标签/配置别名）。
4. **数量**：取子句中距离命中商品最近的数字（阿拉伯数字或中文数字 `一/二/两/…/九十九`），支持量词 `份/个/张/把/台/只/套/瓶/包/盒/件/条/块/杯`；同一商品多个数字或无法解析 → 视为 1 并追加纯文本问题。数量随后夹到 `1..99`。
5. **多候选**：一个子句命中多个商品 → **不产出 item**，产出 1 个问题 + 候选（按名称排序，最多 5 个）。
6. **分类命中**：子句包含 `category` → **只出候选问题，永不进 items**（避免"来点外设/来点咖啡"整类加购）；即使分类下只有一个商品也走候选确认。
7. **未命中**：子句没有任何名称/标签/分类命中 → 进 `unmatched`，`reason=暂无此商品`。
8. **是否需要模型**：存在至少 1 个 `unmatched` 子句且 `app.assistant.enabled=true` 时才调用 Python；否则走快速路径，`degraded=false`。

已知局限：包含式匹配会把"显示器支架"误判为"显示器"。MVP 依赖草稿可编辑与前端核对提示兜底，后续用向量检索收敛（§9）。

### 5.2 Python 模型层

- 协议：OpenAI 兼容 `POST {base-url}/chat/completions`，优先 `response_format={"type":"json_object"}`；供应商不支持时退回纯文本 + 提取首个 JSON 对象。
- **单次调用，不重试**：`max_retries=0`，客户端超时 4000ms（略小于 Java 读超时 4500ms，保证 Java 先拿到 Python 的降级响应而不是自己先超时）。
- 温度 0，`max_tokens` 512。
- 提示词：system 固定（角色、输出 JSON schema、`excludeProductIds`、禁止编造、目录视为数据）；user 消息只放用户原文；目录以紧凑行注入：`id|名称|分类|标签|价格|库存|描述(80字)`。
- 出网：容器需能访问 `base-url` 的 HTTPS 端点；有代理时用 `HTTPS_PROXY`，不写进仓库。
- 提示词模板固化在 `prompt.py`，离线评估复用同一模板。

### 5.3 规则层与模型层的合并

| 情况 | 结果 |
|---|---|
| 模型输出的 `productId` 已在规则 items 中 | 丢弃模型结果，保留规则的数量与 `score`（数量**不跨源相加**） |
| 模型输出的 `productId` 在 `excludeProductIds` 中 | 丢弃（规则已出问题候选） |
| 模型给出规则未命中的商品 | 采纳，`source=MODEL`、`score=0.6` |
| 模型 items 内部重复 | 同 id 先求和再夹到 `1..99` |
| 合并后超过 20 条 | 规则项优先，其余按模型返回顺序截断 |
| `quantity > stock` | 保留条目并置 `stockShortage=true`，**不锁库存** |
| 模型正常 | `unmatched` = 模型的 `unmatched` ∪ 未知 `productId`；若模型未产出任何可用商品，再回退合并规则层 unmatched（按规整文本去重） |
| 模型降级 | `unmatched` = 规则层 unmatched |
| `questions` | 规则问题优先，模型问题补充；合计 ≤5，单条 ≤100 字，去重 |
| `confidence` | `items` 中最小 `score`；模型自报值一律忽略 |

**Java 最终裁决（不可省）**：

1. `productId` 必须存在于本次发送的目录且 `enabled=true`，否则移入 `unmatched`。
2. 数量取整夹到 `1..99`；总条数 ≤20。
3. 价格与金额一律取自数据库（目录快照），模型输出中的任何价格、用户名、订单号、支付字段全部丢弃。
4. 名称以数据库为准，不采用模型输出的商品名。

### 5.4 降级语义与 `degradedReason`

| 场景 | Java 观察 | `degraded` | `degradedReason` | 返回内容 |
|---|---|---|---|---|
| `app.assistant.enabled=false` | 未调用 | true | `DISABLED` | 规则结果 |
| Python 未启动 / 连接拒绝 / DNS 失败 | `IOException` | true | `UNREACHABLE` | 规则结果 |
| 读超时 | `SocketTimeoutException` | true | `TIMEOUT` | 规则结果 |
| Python 返回非 200 | `status != 200` | true | `UPSTREAM_ERROR`（401/400 记 ERROR，提示 token 或契约配置错误） | 规则结果 |
| 响应非 JSON 或字段越界 | Jackson/pydantic 校验失败 | true | `INVALID_OUTPUT` | 规则结果 |
| `200 + status=DEGRADED` | 透传 `reason` | true | 同 `reason` | 规则结果 |
| 模型正常 | `status=OK` | false | 不出现 | 合并结果 |
| 规则全覆盖（未调用） | 无调用 | false | 不出现 | 规则结果 |

- Python 侧缺 `ASSISTANT_API_KEY`/`BASE_URL`/`MODEL` 时不启动失败，而是解析时返回 `DEGRADED + DISABLED`，保证整栈在没有模型配置时也能跑通。
- 所有降级都记 WARN（含 `draftId`、`reason`、耗时），401/400 记 ERROR；**不记录用户原文**。

## 6. 商品模型与数据迁移

### 6.1 Flyway V2：加列

```sql
-- na-order-service/src/main/resources/db/migration/V2__add_product_category_and_tags.sql
ALTER TABLE products ADD COLUMN category VARCHAR(50);
ALTER TABLE products ADD COLUMN tags VARCHAR(200);
```

### 6.2 Flyway V3：幂等回填

已部署环境 `products` 非空，种子不会重跑，必须回填：

```sql
-- na-order-service/src/main/resources/db/migration/V3__backfill_product_category_and_tags.sql
UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '键盘,机械键盘,keyboard,输入设备')
WHERE name = '机械键盘 K87';

UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '鼠标,鼠标器,无线,mouse,输入设备')
WHERE name = '无线鼠标 M330';

UPDATE products SET category = COALESCE(category, '显示'),
                    tags     = COALESCE(tags, '显示器,屏幕,2K,monitor')
WHERE name = '27 英寸显示器';

UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '扩展坞,拓展坞,USB-C,转接,dock')
WHERE name = 'USB-C 扩展坞';

UPDATE products SET category = COALESCE(category, '音频'),
                    tags     = COALESCE(tags, '耳麦,耳机,headset,降噪')
WHERE name = '降噪耳麦';

UPDATE products SET category = COALESCE(category, '家具'),
                    tags     = COALESCE(tags, '椅子,工学椅,座椅,chair')
WHERE name = '人体工学椅';

UPDATE products SET category = COALESCE(category, '照明'),
                    tags     = COALESCE(tags, '灯,氛围灯,灯带,rgb')
WHERE name = '桌面氛围灯';

UPDATE products SET category = COALESCE(category, '外设'),
                    tags     = COALESCE(tags, '鼠标垫,桌垫,配件')
WHERE name = '限量鼠标垫';
```

- 只回填能按商品名确定的 8 个演示 SKU；其它历史行保持 NULL（不猜、不误标），规则层对 NULL 视为无分类/无标签。
- `COALESCE` 保证重复执行与人工修改都不被覆盖。
- 新库顺序：V2 → V3（0 行）→ `DataInitializer` 插入带分类/标签的商品。

### 6.3 代码改动

- `Product` 增加 `category`、`tags` 两个可空列映射，并提供 7 参构造器；保留 5 参构造器（测试在用）。
- `ProductInfo` 增加 `category`、`tags`（唯一构造点在 `ProductService.toInfo`）。
- `DataInitializer` 的 8 个商品补分类/标签（与 V3 完全一致，避免新老库分叉）。
- **`ProductView` 与 `GET /api/products` 响应不变**，前端无回归。
- 不建索引、不建标签关联表：目录整份载入，当前没有按标签筛选商品的查询。

### 6.4 目录快照缓存

`CatalogSnapshotCache` 缓存 `List<ProductInfo>` 60 秒（`volatile` 不可变快照，无新依赖）。库存最多陈旧 60 秒，仅影响草稿上的 `stockShortage` 展示；真正的库存校验仍在 `POST /api/orders` 的条件 UPDATE。

## 7. 配置与部署

### 7.1 订单服务（Java）

```yaml
app:
  assistant:
    enabled: ${APP_ASSISTANT_ENABLED:false}
    base-url: ${APP_ASSISTANT_URL:http://na-assistant-service:8092}
    service-token: ${APP_ASSISTANT_TOKEN:}
    connect-timeout-ms: ${APP_ASSISTANT_CONNECT_TIMEOUT_MS:2000}
    read-timeout-ms: ${APP_ASSISTANT_READ_TIMEOUT_MS:4500}
    max-text-length: 300
    max-items: 20
    max-catalog-size: 100        # 超过则预筛，见 §9
    prefilter-top: 50
    catalog-cache-ttl-ms: 60000
    rate-limit-per-minute: 10
    extra-aliases: {}            # 可选，商品名 -> 追加别名；标签已是别名主来源
```

- 限流为**单实例内存令牌桶**：`ConcurrentHashMap<String, Bucket>` 按用户名计数，访问时清理 1 小时未活跃的 key。多实例部署前需改为网关/Redis 限流（记入 §13）。
- `enabled=false`、token 为空、Python 不可达 → 只走规则层，接口照常 200，`degraded=true` + `degradedReason`。

### 7.2 assistant 服务（Python，环境变量）

```
ASSISTANT_INTERNAL_TOKEN   # = .env 的 ASSISTANT_SERVICE_TOKEN
ASSISTANT_BASE_URL         # 例：https://api.deepseek.com/v1
ASSISTANT_MODEL            # 例：deepseek-chat
ASSISTANT_API_KEY
ASSISTANT_TIMEOUT_MS=4000  # 略小于 Java 读超时 4500
ASSISTANT_MAX_BODY_BYTES=262144
ASSISTANT_DEBUG=0          # 仅 1 时暴露 /docs、/redoc、/openapi.json
```

### 7.3 docker-compose

```yaml
  na-assistant-service:
    build:
      context: .
      dockerfile: na-assistant-service/Dockerfile
    image: na-assistant-service:local
    environment:
      ASSISTANT_INTERNAL_TOKEN: ${ASSISTANT_SERVICE_TOKEN:-}
      ASSISTANT_BASE_URL: ${ASSISTANT_BASE_URL:-}
      ASSISTANT_MODEL: ${ASSISTANT_MODEL:-}
      ASSISTANT_API_KEY: ${ASSISTANT_API_KEY:-}
      ASSISTANT_DEBUG: "0"
      ASSISTANT_TIMEOUT_MS: "4000"
      TZ: Asia/Shanghai
    expose:
      - "8092"
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request;urllib.request.urlopen('http://localhost:8092/health')"]
      interval: 15s
      timeout: 3s
      retries: 5
      start_period: 10s
    mem_limit: 256m
    restart: unless-stopped
```

订单服务新增环境变量：

```yaml
      APP_ASSISTANT_ENABLED: "false"
      APP_ASSISTANT_URL: http://na-assistant-service:8092
      APP_ASSISTANT_TOKEN: ${ASSISTANT_SERVICE_TOKEN:-}
```

- **不复用 `INTERNAL_SERVICE_TOKEN`**：AI 服务被攻破不应连带获得支付服务的调用权。
- 订单服务仍不 `depends_on` 该服务。
- 默认 `APP_ASSISTANT_ENABLED=false`，`docker compose` 的既有行为与验收路径不变。

### 7.4 本地调试

宿主 Java 直连容器内 Python 时用覆盖文件（不进默认路径）：

```yaml
# docker-compose.assistant-dev.yml
services:
  na-assistant-service:
    ports:
      - "127.0.0.1:8092:8092"
```

```bash
docker compose -f docker-compose.yml -f docker-compose.assistant-dev.yml up -d
APP_ASSISTANT_ENABLED=true APP_ASSISTANT_URL=http://localhost:8092 APP_ASSISTANT_TOKEN=... \
  ./mvnw.cmd -pl na-order-service spring-boot:run
```

### 7.5 忽略规则

`.gitignore` 追加：

```
__pycache__/
*.py[cod]
.venv/
venv/
.pytest_cache/
.ruff_cache/
```

根 `.dockerignore` 追加：

```
**/__pycache__/
**/*.py[cod]
na-assistant-service/tests/
na-assistant-service/.venv/
```

### 7.6 `.env.example`

追加（值一律留空，由使用者自行填写；**不在会话中索要或回显**）：

```
# AI 智能下单（可选）
ASSISTANT_SERVICE_TOKEN=
ASSISTANT_BASE_URL=
ASSISTANT_MODEL=
ASSISTANT_API_KEY=
```

## 8. 安全设计

| 风险 | 措施 |
|---|---|
| 未授权调用 Python | 路由级 token 依赖（默认拒绝，仅 `/health` 不挂依赖），`hmac.compare_digest` 比较；不映射宿主端口；**不按路径前缀放行**（吸取 P0-2 编码路径绕过的教训） |
| 接口文档外泄 | `ASSISTANT_DEBUG=0` 时不注册 `/docs`、`/redoc`、`/openapi.json` |
| 请求体滥用 | 中间件按 `Content-Length` 拒绝 >256KB（返回 413）；pydantic 限制 `catalog ≤100`、`excludeProductIds ≤100`、`text ≤2000` |
| 容器逃逸面 | Dockerfile 建非 root 用户并 `USER`；`python:3.11-slim` + `pip --no-cache-dir` |
| 提示词注入 | system message 固定且不含 token/库结构/内部地址；用户文本只作 user message；目录描述视为数据；输出 pydantic + Java 双重校验 |
| 编造商品 | Java 侧 `productId` 白名单（本次目录）+ 回查 `enabled` |
| 价格篡改 | 响应价格全部来自数据库 |
| 数量与库存滥用 | 单条 1–99、最多 20 条；规则与模型结果都夹取；按用户限流 10 次/分钟 |
| 密钥泄露 | 模型 key 只在 Python 容器环境变量；Java 只持有 AI 专用内部 token；日志不打印任何 token |
| 隐私 | 用户原文不入日志（只记 `draftId`、长度、`sha256` 前 8 位）；文本会发送给模型供应商，页面明示 |
| 故障扩散 | Python 不可达/超时/非法输出 → 规则层兜底；订单与支付主链路不受影响 |
| CSRF | `/api/**` 沿用现有 `ignoringAntMatchers`（KI-05）；本接口不写库、不建单，风险限于消耗模型配额 |

## 9. 容量与演进阈值

| 条件 | 应对 |
|---|---|
| 目录 ≤100 SKU | 整份目录进提示词（当前 8 个） |
| 目录 >100 SKU | 先按"文本包含标签/名称/分类"打分预筛 Top 50；命中不足 10 个时按 id 取前 50，并记 WARN |
| 目录 >1000 SKU | 在 Python 侧引入 embedding 检索，Java 契约不变 |
| 需要多轮澄清 / 工具调用 / 流式 | 只在 Python 服务内演进，Java 契约不变 |
| 需要审计"AI 建议过什么" | 再加 `assistant_draft` 表 |

## 10. 测试策略

### 10.1 Java 侧（不依赖 Python 与外网）

| 类 | 覆盖 |
|---|---|
| `CatalogMatcherTest` | 分句、中文数字、量词、标签别名、最长匹配、多候选问题、分类只出候选、无命中、数量无法解析 |
| `AssistantDraftServiceTest` | 规则优先且同 id 不叠加数量、`excludeProductIds` 丢弃、模型补位、未知 productId 移入 unmatched、合并去重、数量夹取、库存不足、目录超 100 预筛、60s 缓存命中 |
| `AssistantDegradationTest` | 关闭/连接失败/超时/非 200/非法 JSON/`DEGRADED` 透传，逐项断言 `degraded` 与 `degradedReason`，且 HTTP 恒为 200 |
| `AssistantApiTest` | 未登录 401、空文本/超长 400（`{message}` 形状）、限流 429、正常 200 结构、无 items 时 `confidence=0` |
| `AssistantContractTest` | 读取 `tests/contract/*.json`，断言 Java DTO 与 §4.2 契约完全对应（字段名、可空性、枚举值） |
| `FakeAssistantClient` | test profile 注入，按用例返回 OK/DEGRADED/异常 |

### 10.2 Python 侧（pytest，不依赖真实模型）

| 用例 | 覆盖 |
|---|---|
| `test_auth.py` | 无 token / 错 token → 401；`/health` 免鉴权；**`/internal/v1/assistant/parse` 无依赖漏配** |
| `test_schemas.py` | 请求校验、catalog 超 100、exclude 超 100、text 超长 |
| `test_parse.py` | 假模型：正常 JSON、非法 JSON、超时、供应商 500、缺配置 → 200 + `DEGRADED` + 正确 `reason` |
| `test_limits.py` | 超 256KB → 413；`ASSISTANT_DEBUG=0` 时 `/docs` 404 |
| `test_contract.py` | 用 `tests/contract/*.json` 断言 pydantic 模型双向可解析 |
| `test_privacy.py` | 日志不含原文（`caplog` 断言） |

### 10.3 跨语言契约 fixture

```
tests/contract/
├── assistant-parse.request.json     # Java 序列化 → Python 反序列化
├── assistant-parse.ok.json          # Python 响应（正常）
└── assistant-parse.degraded.json    # Python 响应（降级）
```

- Java 测试通过 `../tests/contract`（相对模块工作目录）读取，找不到时回退 `tests/contract`。
- pytest 用 `Path(__file__).parents[2] / "tests" / "contract"` 定位。
- 任一端改字段必须先改 fixture，两侧测试同时失败。

### 10.4 手工评估（不进门禁）

`tests/eval/assistant-golden.md` 维护 20–30 条真实说法与期望商品，人工对真实模型跑一轮，记录命中率与人工改单率。

## 11. 里程碑

| 阶段 | 交付 | 完成标准 |
|---|---|---|
| M1 | Flyway V2 + V3、`Product`/`ProductInfo`/种子标签、目录缓存、规则层、草稿接口、`AssistantClient` 空实现（恒 `DEGRADED/DISABLED`）、`degradedReason` | `mvnw test` 全绿；**Compose PostgreSQL** 上重建/复用数据卷各验一次 V2+V3 与 `ddl-auto=validate`；登录后调用草稿接口返回验收场景 A/B/C，且 `degraded=true`；未登录 401 |
| M2 | Python 服务（FastAPI + token + pydantic + 提示词 + 模型客户端）+ compose + Java HTTP 客户端 + 契约 fixture | 关闭容器/断外网/返回非法 JSON/错 token，均在 4.5s 内降级返回 200，`degradedReason` 正确；pytest 全绿 |
| M3 | 前端输入框、草稿卡片、候选点选、"加入购物车" | 商品页可直接输入；未识别/歧义/降级提示清晰；原有手动路径无回归 |
| M4 | 测试、文档、部署验证 | Java + pytest 全绿；README/AGENTS 更新（含 Python 服务）；默认 `docker compose` 行为不变 |

## 12. 已确认决策

| 项 | 决定 |
|---|---|
| 服务形态 | 独立 `na-assistant-service`（Python/FastAPI），仅内网、内部 token、无宿主端口 |
| 模型接入 | OpenAI 兼容云 API，`base-url` / `model` / `api-key` 走环境变量 |
| 种子数据 | 保留现有 8 个外设 SKU；改验收文案；V3 幂等回填历史行的 category/tags |
| 商品模型 | 增加 `category`、`tags` 可空字段；`tags` 即别名主来源；不进 `ProductView` |
| 分类命中 | 只产生候选与问题，永不直接进 items |
| 合并策略 | 规则命中优先，模型只补 unmatched；数量不跨源相加 |
| 降级语义 | Python 归一 `200 + DEGRADED + reason`；Java 映射为 5 种 `degradedReason`；浏览器永不 5xx |
| 置信度 | Java 按来源计算（0.9/0.7/0.6），忽略模型自报值 |
| 错误结构 | 沿用 `{message, fieldErrors?}`，不新增 `code` |
| 隐私提示 | 输入框下方固定提示「输入内容会发送给第三方模型服务用于解析」 |
| 歧义处理 | 只返回 `questions`（可带候选），不做多轮追问 |
| Java 版本 | 不为本功能升级 Java 8；升级 JDK 另立计划 |

### 12.1 待补充（不阻塞 M1）

- 具体供应商与模型名（DeepSeek / 通义 / 智谱等）。M2 联调前提供 `base-url` 与 `model`；**`api-key` 由用户写进 `.env`，不提交、不粘贴到会话**。

## 13. 风险

- **包含式匹配误判**：如"显示器支架"命中"显示器"。MVP 用草稿可编辑 + 低置信度提示兜底，后续向量检索收敛。
- **跨语言契约漂移**：用 `tests/contract` fixture 双端断言，Python 只返回最小字段集。
- **模型输出不稳定**：严格 schema + 规则层兜底 + golden set 评估，不追求"一次说对"。
- **成本**：目录小、提示词短、默认关闭、限流 10 次/分钟；上线前用真实账户测一轮。
- **隐私与合规**：文本出网是硬约束，README 与页面均需明示。
- **限流与缓存为单实例**：多实例部署前需网关/Redis 限流与共享缓存。
- **草稿与真实库存不同步**：目录缓存 60s、草稿不锁库存，结算仍可能失败，沿用现有错误提示。
- **规则分词依赖标点与连接词**："和/跟"等连接词可能误切专有名词，靠用例覆盖，必要时改为白名单。
- **迁移风险**：V2/V3 需同时通过 H2 与 PG 的 `validate`，且要在已有数据卷上实测回填。
- **多一个故障域**：Python 容器挂了不影响既有下单与支付，只影响 AI 解析质量。
