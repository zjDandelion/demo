# na-assistant-java 使用说明

Java 版下单助手服务，与 `na-assistant-service`（Python/FastAPI）**契约完全兼容**，
两个服务可同时运行，由订单侧的 `APP_ASSISTANT_URL` 决定使用哪一个。

设计背景与实施计划见 [assistant-java-plan.md](assistant-java-plan.md)。

## 1. 为什么要两个实现

| 目的 | 说明 |
|---|---|
| 技术栈统一 | 除助手服务外全部是 Java，减少一套 Python 工具链的维护成本 |
| 可回退 | Python 版永久保留，切换只改一个环境变量，秒级回退 |
| 契约固化 | 两边跑同一份 `tests/contract` fixture，任何一方改契约都会立刻失败 |

## 2. 服务职责

```
订单服务 ──POST /internal/v1/assistant/parse──> 助手服务 ──> OpenAI 兼容模型
```

只做一件事：把用户的中文自然语言转成购物车草稿 JSON。

**不做**：库存校验、价格计算、持久化。这些都在订单服务里。

## 3. 接口契约

### 请求

```http
POST /internal/v1/assistant/parse
X-Service-Token: <ASSISTANT_SERVICE_TOKEN>
Content-Type: application/json

{
  "requestId": "drf_5f2c9a1b",
  "userId": 1,
  "text": "帮我拿两个机械键盘，再加一张升降桌",
  "excludeProductIds": [1],
  "catalog": [
    {"id": 1, "name": "机械键盘 K87", "description": "...", "category": "外设",
     "tags": "键盘,机械键盘", "price": 299.00, "stock": 25}
  ]
}
```

**未知字段会被拒绝**（400），与 Python 的 `extra="forbid"` 一致。

### 成功响应（HTTP 200）

```json
{
  "status": "OK",
  "items": [{"productId": 1, "quantity": 2}],
  "unmatched": [{"text": "升降桌", "reason": "暂无此商品"}],
  "questions": [],
  "usage": {"promptTokens": 620, "completionTokens": 48, "latencyMs": 870}
}
```

### 降级响应（**也是 HTTP 200**）

```json
{
  "status": "DEGRADED",
  "reason": "TIMEOUT",
  "items": [], "unmatched": [], "questions": [],
  "usage": {"promptTokens": 0, "completionTokens": 0, "latencyMs": 4000}
}
```

| reason | 触发条件 |
|---|---|
| `DISABLED` | 未配置 `baseUrl` / `model` / `apiKey` |
| `TIMEOUT` | 上游超时 |
| `UPSTREAM_ERROR` | 上游 4xx/5xx 或网络错误 |
| `INVALID_OUTPUT` | 模型输出无法解析 |

**注意**：降级不是错误，返回码仍是 200。这是与订单侧约定好的语义——降级后仍然能用规则层结果下单。

### 其他响应

| 场景 | 状态码 | 响应体 |
|---|---|---|
| 无 token / 错 token | 401 | `{"message": "unauthorized"}` |
| 请求结构不合契约 | 400 | `{"message": "请求结构不合契约"}` |
| 目录/排除/文本超上限 | 400 | `{"message": "目录超过上限"}` 等 |
| 请求体超过 256KB | 413 | `{"message": "请求体过大"}` |
| 健康检查 | 200 | `{"status": "UP"}`（无需 token） |

## 4. 配置

环境变量与 Python 版**完全同名**，可直接共用 `.env`：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ASSISTANT_INTERNAL_TOKEN` | 空 | 内部 token，空则拒绝所有请求 |
| `ASSISTANT_BASE_URL` | 空 | OpenAI 兼容端点 |
| `ASSISTANT_MODEL` | 空 | 模型名 |
| `ASSISTANT_API_KEY` | 空 | API key |
| `ASSISTANT_TIMEOUT_MS` | 4000 | 上游超时 |
| `ASSISTANT_MAX_BODY_BYTES` | 262144 | 请求体上限 |
| `ASSISTANT_MAX_CATALOG` | 100 | 目录条数上限 |
| `ASSISTANT_MAX_EXCLUDE` | 100 | 排除列表上限 |
| `ASSISTANT_MAX_TEXT_LENGTH` | 2000 | 文本长度上限 |

## 5. 运行与切换

### 启动 Java 版（与 Python 版并存）

```bash
docker compose --profile java-assistant up -d na-assistant-java
```

### 切换

```bash
bash scripts/switch-assistant.sh java     # 切到 Java 版
bash scripts/switch-assistant.sh python   # 切回 Python 版
bash scripts/switch-assistant.sh status   # 查看当前指向
```

脚本会：启动目标服务（如果是 Java）→ 等健康检查 → 写 `.env` → 重建订单服务 → 验证。

### 双跑对照

两个服务都跑着时，可以对比同一输入的输出：

```bash
bash scripts/compare-assistant.sh "帮我拿两个机械键盘，再加一张升降桌"
```

脚本会归一化后 diff（忽略 `usage.latencyMs`）。

## 6. 本地开发

```bash
# 只跑测试
./mvnw -pl na-assistant-java -am test

# 本地启动（不通过 Docker）
./mvnw -pl na-assistant-java spring-boot:run
# 默认端口 8093
```

## 7. 测试

44 个测试，分四类：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `AssistantContractTest` | 6 | 复用 `tests/contract` fixture，验证请求/响应结构与未知字段策略 |
| `PromptBuilderTest` | 8 | **prompt 与 Python 逐字符一致**、目录渲染、截断、排序 |
| `JsonExtractorTest` | 10 | 裸 JSON、代码围栏、前后噪声、字符串内括号、转义、非法输入 |
| `OutputSanitizerTest` | 10 | 越界 id、排除列表、重复合并、数量夹取、各维度上限 |
| `ParseControllerTest` | 10 | 鉴权、健康检查、四种降级、400 校验、编码路径绕过 |

### 契约测试的意义

`tests/contract/*.json` 由三方共用：

- Java 助手服务（本模块 `AssistantContractTest`）
- Python 助手服务（`test_contract.py`）
- 订单服务（`AssistantContractTest`）

任何一方改了字段，另外两方会立刻失败。这是「换实现不换契约」的保障。

## 8. 与 Python 版的实现差异

功能完全等价，实现方式不同：

| 方面 | Python | Java |
|---|---|---|
| Web 框架 | FastAPI | Spring Boot Web |
| JSON | pydantic | Jackson |
| HTTP 客户端 | httpx | RestTemplate |
| 鉴权 | FastAPI dependency | Servlet Filter |
| 未知字段 | `extra="forbid"` | `FAIL_ON_UNKNOWN_PROPERTIES` |
| 十进制 | `Decimal` | `BigDecimal` |
| 端口 | 8092 | 8093 |

**唯一的语义差异**：Python 的 `text` 字段 schema 上限是 4000、业务上限是 2000；
Java 版统一按 2000 检查（更严格）。如需完全一致，改 `application.yml` 的
`max-text-length` 即可。

## 9. 排查

| 现象 | 检查 |
|---|---|
| 订单侧 AI 草稿一直 `degraded=true` | `bash scripts/switch-assistant.sh status` 看指向；`docker compose logs na-assistant-java` |
| 401 | `.env` 里 `ASSISTANT_SERVICE_TOKEN` 与容器内 `ASSISTANT_INTERNAL_TOKEN` 是否一致 |
| 400 请求结构不合契约 | 订单侧发来的字段是否超出契约（用 `tests/contract` fixture 对照） |
| `DISABLED` | `ASSISTANT_BASE_URL` / `ASSISTANT_MODEL` / `ASSISTANT_API_KEY` 是否都填了 |
