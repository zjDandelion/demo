# na-assistant-java 实施计划

> 目标：用 Java 重写 `na-assistant-service`（Python/FastAPI），保持契约完全兼容，
> 原 Python 服务永久保留作为备用，通过环境变量切换。
>
> 状态：**待实施**
> 配套文档：[AI 下单设计](ai-ordering-design.md) §4.2、[已知问题](known-issues.md)

## 1. 已确认的 4 个决策

| 决策 | 选择 | 说明 |
|---|---|---|
| 模块形态 | **A. 新建 `na-assistant-java` 独立模块** | 保持独立服务，架构对称，可独立扩缩 |
| 切换方式 | **A. 环境变量 `APP_ASSISTANT_URL`** | 两套服务同时存在，改 URL 即切换，秒级回退 |
| Python 服务 | **A. 永久保留** | 作为备用通道，CI 里持续验证 |
| 契约测试 | **A. 复用 `tests/contract` fixture** | 同一份 fixture 双向校验，证明兼容 |

## 2. 为什么可行

Python 服务没有使用任何 Python 生态独有能力：

- 无 numpy/pandas/异步框架特性/专属库；
- 对外契约只有 **1 个接口**：`POST /internal/v1/assistant/parse`；
- 核心逻辑是「拼 prompt → 调 OpenAI 兼容接口 → 解析 JSON → 清洗」；
- 已有跨语言契约 fixture（`tests/contract/assistant-parse.*.json`），正是为换实现准备的。

## 3. 必须精确复刻的语义（逐条对照 Python 源码）

这是本计划最关键的部分。任何一条不一致都会导致契约测试失败或行为漂移。

### 3.1 请求校验

| 规则 | Python 实现 | Java 对应 |
|---|---|---|
| 未知字段 | `ParseRequest` 用 `extra="forbid"` → **拒绝** | `@JsonIgnoreProperties(ignoreUnknown = false)` + 全局 `FAIL_ON_UNKNOWN_PROPERTIES=true` |
| 子对象未知字段 | `CatalogItem`/`ParsedItem` 等用 `extra="ignore"` → 忽略 | 这些类单独标 `@JsonIgnoreProperties(ignoreUnknown = true)` |
| `requestId` | 1–64 字符 | `@Size(min=1, max=64)` |
| `text` | 1–4000（schema），但 `main.py` 额外限制 ≤2000 | `@Size(max=4000)` + 手工检查 2000 |
| `catalog` 长度 | ≤100 | 手工检查 |
| `excludeProductIds` 长度 | ≤100 | 手工检查 |
| 请求体大小 | ≤262144 字节 | `server.tomcat.max-http-form-post-size` + 过滤器 |
| `price` 精度 | `Decimal` | `BigDecimal` + `USE_BIG_DECIMAL_FOR_FLOATS` |

**校验失败响应**：`400` + `{"message": "请求结构不合契约"}`（注意字段名是 `message`，不是 `error`）。

### 3.2 鉴权

- Header：`X-Service-Token`
- 常量时间比较（Python 用 `hmac.compare_digest`，Java 用 `MessageDigest.isEqual` 比较 UTF-8 字节）
- 缺失或不匹配：`401` + `{"message": "unauthorized"}`
- **拒绝优先**：只精确放行 `/health` 和 `/error`，其余路径一律校验 token

### 3.3 上游模型调用

```
POST {baseUrl}/chat/completions
Authorization: Bearer {apiKey}
{
  "model": "{model}",
  "messages": [ ... ],
  "temperature": 0,
  "max_tokens": 512,
  "response_format": {"type": "json_object"}
}
```

超时：`ASSISTANT_TIMEOUT_MS`，默认 4000ms，最小值 100ms。

### 3.4 Prompt（必须逐字一致）

系统提示词与目录块格式见 `na-assistant-service/app/prompt.py`：

```
系统提示：你是电商下单助手...（6 条规则，原文照搬）

第二个 system 消息：
已解析商品（禁止再次输出）：{逗号分隔的 id，或"无"}
可选目录（id|名称|分类|标签|价格|库存|描述）：
{每行：id|name|category|tags|price|stock|description前80字}

user 消息：{text}
```

**注意**：`description` 截断到 80 字并去掉换行；`price` 用 `Decimal` 的字符串形式（`299.00` 而非 `299.0`）。

### 3.5 输出解析

Python 的 `_extract_json` 做了三层兜底，必须复刻：

1. 直接 `json.loads`；
2. 去掉 ``` 代码块围栏（含 ```json 前缀）；
3. 从第一个 `{` 开始按括号配对扫描（正确处理字符串内的 `{`/`}` 和转义）。

### 3.6 清洗规则（`_sanitize`）

| 项 | 规则 |
|---|---|
| items 过滤 | `productId` 不在 `request.catalog` 中，或在 `excludeProductIds` 中 → 丢弃 |
| items 合并 | 同一 `productId` 累加，上限 99 |
| items 顺序 | 保留首次出现顺序 |
| items 上限 | 20 条 |
| quantity | 夹到 1–99 |
| unmatched | `text` 去空白、去重、空则跳过；`reason` 空则默认 `"暂无此商品"`；上限 10 条 |
| questions | `text` 去空白、截断 100 字、去重；上限 5 条 |

### 3.7 降级语义

四个原因，**全部返回 HTTP 200**（这是关键，不是 5xx）：

| 原因 | 触发条件 |
|---|---|
| `DISABLED` | 未配置 `baseUrl`/`model`/`apiKey` 任一 |
| `TIMEOUT` | 上游超时 |
| `UPSTREAM_ERROR` | 上游 4xx/5xx 或网络错误 |
| `INVALID_OUTPUT` | 模型输出无法解析 / 缺 `choices` / JSON 不合法 |

降级响应结构：

```json
{
  "status": "DEGRADED",
  "reason": "TIMEOUT",
  "items": [],
  "unmatched": [],
  "questions": [],
  "usage": {"promptTokens": 0, "completionTokens": 0, "latencyMs": 4000}
}
```

**未捕获异常也返回 200 + `DEGRADED/UPSTREAM_ERROR`**（Python 的兜底 handler）。

### 3.8 健康检查

`GET /health` → `{"status": "UP"}`，无需 token。

## 4. 模块结构

```
na-assistant-java/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/example/na/assistant/
    │   │   ├── NaAssistantJavaApplication.java
    │   │   ├── config/
    │   │   │   ├── AssistantProperties.java      # @ConfigurationProperties
    │   │   │   ├── InternalTokenFilter.java      # 复用支付服务写法
    │   │   │   ├── JacksonConfig.java            # BigDecimal + 严格未知字段
    │   │   │   └── RestClientConfig.java         # 超时配置
    │   │   ├── web/
    │   │   │   ├── ParseController.java          # POST /internal/v1/assistant/parse
    │   │   │   ├── HealthController.java         # GET /health
    │   │   │   ├── GlobalExceptionHandler.java   # 400/401/500 → message 结构
    │   │   │   ├── ParseRequest.java             # ignoreUnknown=false
    │   │   │   ├── CatalogItem.java
    │   │   │   ├── ParseResponse.java
    │   │   │   ├── ParsedItem.java
    │   │   │   ├── ParsedUnmatched.java
    │   │   │   ├── ParsedQuestion.java
    │   │   │   ├── Usage.java
    │   │   │   └── ErrorResponse.java
    │   │   ├── client/
    │   │   │   ├── ChatCompletionClient.java     # RestTemplate
    │   │   │   ├── ChatCompletionRequest.java
    │   │   │   ├── ChatCompletionResponse.java
    │   │   │   ├── ModelOutput.java
    │   │   │   └── UpstreamStatusException.java
    │   │   ├── service/
    │   │   │   ├── PromptBuilder.java            # 逐字复刻 prompt.py
    │   │   │   ├── JsonExtractor.java            # 三层兜底解析
    │   │   │   ├── OutputSanitizer.java          # 清洗规则
    │   │   │   └── ParseService.java             # 编排 + 降级
    │   │   └── domain/
    │   │       └── DegradedReason.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/example/na/assistant/
        ├── contract/AssistantContractTest.java   # 复用 tests/contract fixture
        ├── service/PromptBuilderTest.java
        ├── service/JsonExtractorTest.java
        ├── service/OutputSanitizerTest.java
        ├── web/ParseControllerTest.java
        ├── web/InternalAuthTest.java
        └── client/ChatCompletionClientTest.java
```

**包名**：`com.example.na.assistant`（与 `com.example.na.order.assistant` 分属不同模块，不冲突）。

**端口**：`8093`（Python 占 8092）。

## 5. 配置项

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `ASSISTANT_INTERNAL_TOKEN` | 空 | 内部鉴权 token，空则拒绝所有请求 |
| `ASSISTANT_BASE_URL` | 空 | OpenAI 兼容端点 |
| `ASSISTANT_MODEL` | 空 | 模型名 |
| `ASSISTANT_API_KEY` | 空 | API key |
| `ASSISTANT_TIMEOUT_MS` | 4000 | 上游超时 |
| `ASSISTANT_MAX_BODY_BYTES` | 262144 | 请求体上限 |
| `ASSISTANT_MAX_CATALOG` | 100 | 目录条数上限 |
| `ASSISTANT_MAX_EXCLUDE` | 100 | 排除列表上限 |
| `ASSISTANT_MAX_TEXT_LENGTH` | 2000 | 文本长度上限 |
| `ASSISTANT_DEBUG` | 0 | 1 时暴露 swagger |

**与 Python 完全同名**，便于共用 `.env`。

## 6. 部署与切换

### 6.1 Compose 定义

两个服务都定义，Java 版默认**不启动**（profile 控制）：

```yaml
na-assistant-service:        # Python，现有，保持不变
  ...
na-assistant-java:           # Java，新增
  profiles: ["java-assistant"]
  image: ${NA_ASSISTANT_JAVA_IMAGE:-na-assistant-java:local}
  expose: ["8093"]
  environment:
    ASSISTANT_INTERNAL_TOKEN: ${ASSISTANT_SERVICE_TOKEN:-}
    ASSISTANT_BASE_URL: ${ASSISTANT_BASE_URL:-}
    ASSISTANT_MODEL: ${ASSISTANT_MODEL:-}
    ASSISTANT_API_KEY: ${ASSISTANT_API_KEY:-}
    ASSISTANT_TIMEOUT_MS: "4000"
  healthcheck:
    test: ["CMD", "curl", "-fsS", "http://localhost:8093/health"]
```

### 6.2 切换步骤

```bash
# 1. 启动 Java 版
docker compose --profile java-assistant up -d na-assistant-java

# 2. 切换订单服务指向（改 .env）
APP_ASSISTANT_URL=http://na-assistant-java:8093
APP_ASSISTANT_ENABLED=true

# 3. 重建订单服务
bash scripts/pull-deploy.sh <tag>     # 或 docker compose up -d na-order-service

# 4. 验证
curl -s localhost:8081/api/assistant/drafts -H 'Content-Type: application/json' \
  -d '{"text":"帮我拿两个机械键盘"}' -b cookies.txt

# 5. 回退（秒级）
#    把 APP_ASSISTANT_URL 改回 http://na-assistant-service:8092 即可
```

### 6.3 双跑对照（灰度期）

两个服务同时跑，用同一份输入对比响应：

```bash
bash scripts/compare-assistant.sh   # 新增脚本，见 §8
```

## 7. CI 改动

`.github/workflows/ci.yml`：

1. `java` job 自动覆盖新模块（`mvnw verify` 会编译所有模块）；
2. `image` job 增加构建 `na-assistant-java` 镜像；
3. `python` job 保持不变（Python 服务仍在维护）。

`.github/workflows/release.yml`：

4. 增加推送 `ghcr.io/<owner>/na-assistant-java:<tag>`。

## 8. 新增脚本

| 脚本 | 作用 |
|---|---|
| `scripts/compare-assistant.sh` | 同一输入同时打 Python/Java，输出差异（灰度期用） |
| `scripts/switch-assistant.sh <python\|java>` | 一键切换 `APP_ASSISTANT_URL` 并重建订单服务 |

## 9. 测试策略

### 9.1 契约测试（最高优先级）

`AssistantContractTest` 直接读 `tests/contract/*.json`，断言：

- `assistant-parse.request.json` 能被 `ParseRequest` 反序列化，且字段值一致；
- `assistant-parse.ok.json` 能被 `ParseResponse` 序列化/反序列化，且结构一致；
- `assistant-parse.degraded.json` 同上；
- **序列化后再反序列化，字段集合与 fixture 完全一致**（用现有 `assertContainsContractFields` 的等价逻辑）。

### 9.2 行为一致性测试

| 测试 | 覆盖 |
|---|---|
| `PromptBuilderTest` | prompt 与 Python 输出**逐字符相同**（把 Python 实际输出作为断言基准） |
| `JsonExtractorTest` | 裸 JSON / 带围栏 / 前后有噪声 / 字符串内含括号 / 转义 |
| `OutputSanitizerTest` | 越界 id 丢弃、排除列表、重复合并上限 99、数量夹取、超 20 条截断、unmatched 去重、questions 截断 100 字 |
| `ParseControllerTest` | 正常 OK、四种降级、400 校验失败、413 请求体过大 |
| `InternalAuthTest` | 无 token 401、错 token 401、`/health` 免鉴权、编码路径绕过 |
| `ChatCompletionClientTest` | 超时 → TIMEOUT、4xx/5xx → UPSTREAM_ERROR、无 choices → INVALID_OUTPUT |

### 9.3 端到端

在 CI 之外手工验证（需要真实模型或 mock）：

```bash
# 同一输入，两个服务输出对比
bash scripts/compare-assistant.sh "帮我拿两个机械键盘，再加一张升降桌"
```

## 10. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| Jackson 默认忽略未知字段 | 契约测试失败 | 显式配置 `FAIL_ON_UNKNOWN_PROPERTIES=true`，`ParseRequest` 不标 ignore |
| `BigDecimal` 精度差异 | `299.00` vs `299.0` | 全局 `USE_BIG_DECIMAL_FOR_FLOATS`，比较用 `compareTo` |
| 降级返回 5xx | 订单侧 `ParseOutcome` 判定为失败，行为变化 | 全部降级路径统一 200，测试覆盖 |
| Prompt 文本漂移 | 模型输出质量变化 | 用 Python 实际输出作为断言基准，逐字符比对 |
| 常量时间比较用 `String.equals` | 时序侧信道 | 用 `MessageDigest.isEqual` 比较字节数组 |
| 新增服务占用内存 | WSL 内存紧张 | profile 控制，默认不启动 |

## 11. 工作量拆解

| 阶段 | 内容 | 预估 |
|---|---|---|
| 1 | 模块骨架：pom、启动类、application.yml、Dockerfile | 1h |
| 2 | 数据模型 + Jackson 配置（含未知字段策略） | 1.5h |
| 3 | 鉴权过滤器 + 健康检查 + 异常处理 | 1h |
| 4 | `PromptBuilder`（逐字复刻）+ 单测 | 1.5h |
| 5 | `ChatCompletionClient` + 单测 | 1.5h |
| 6 | `JsonExtractor` + `OutputSanitizer` + 单测 | 2h |
| 7 | `ParseService` 编排 + 降级 + 单测 | 1.5h |
| 8 | 契约测试（复用 fixture） | 1h |
| 9 | Compose / CI / 脚本 / 文档 | 1.5h |
| 10 | 端到端验证 + 双跑对照 | 1h |
| **合计** | | **约 1.5 天** |

## 12. 验收标准

- [ ] `./mvnw clean verify` 通过，新模块测试全绿；
- [ ] 契约测试：同一份 `tests/contract` fixture，Python 与 Java 都通过；
- [ ] `PromptBuilderTest` 的 prompt 与 Python 输出逐字符一致；
- [ ] 四种降级路径都返回 HTTP 200 + 正确 `reason`；
- [ ] 无 token / 错 token 返回 401，`/health` 免鉴权；
- [ ] 编码路径绕过测试（`/%69nternal/...`）被拒绝；
- [ ] 通过 `APP_ASSISTANT_URL` 切换后，订单侧 AI 草稿功能正常；
- [ ] 切回 Python 服务同样正常（验证备用通道可用）；
- [ ] CI 全绿，镜像成功推送 GHCR。

## 13. 实施顺序建议

1. **先做 2、4、6**（数据模型、PromptBuilder、Sanitizer）——这三块是纯逻辑，最容易用单元测试锁定与 Python 的一致性；
2. **再做 3、5、7**（鉴权、客户端、编排）——涉及 IO，需要 mock；
3. **最后做 8、9**（契约测试、部署）——前两步绿了再做，避免返工。

这样安排的好处：即使中途停下，已完成的部分也能独立验证正确性。
