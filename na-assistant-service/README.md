# na-assistant-service

AI 智能下单的模型层：接收订单服务发来的自然语言与商品目录，调用 OpenAI 兼容接口返回结构化购物车草稿。**不连数据库、不查用户、不写任何数据**。

设计与接口契约见仓库根目录 `docs/ai-ordering-design.md`（§4.2、§5.2、§7.2）。

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | 无 | 健康检查 |
| POST | `/internal/v1/assistant/parse` | `X-Service-Token` | 解析商品意图 |

仅内网使用：`docker-compose.yml` 只声明 `expose: 8092`，不映射宿主端口。鉴权是按路由挂载的依赖（默认拒绝），没有按路径前缀放行，避免编码路径绕过。

响应恒为 `200`：正常 `status=OK`，任何模型侧失败返回 `status=DEGRADED` + `reason`（`DISABLED` / `TIMEOUT` / `UPSTREAM_ERROR` / `INVALID_OUTPUT`），由 Java 侧映射为 `degradedReason`。配置不合契约（400）、token 不匹配（401）、请求体超 256KB（413）除外。

## 环境变量

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `ASSISTANT_INTERNAL_TOKEN` | 是 | 空 | 与订单服务共享的 AI 专用 token，空值时一切请求 401 |
| `ASSISTANT_BASE_URL` | 是 | 空 | 例 `https://api.deepseek.com/v1` |
| `ASSISTANT_MODEL` | 是 | 空 | 例 `deepseek-chat` |
| `ASSISTANT_API_KEY` | 是 | 空 | 模型密钥，只存在于本容器 |
| `ASSISTANT_TIMEOUT_MS` | 否 | 4000 | 单次模型调用超时，须小于 Java 读超时 4500 |
| `ASSISTANT_MAX_BODY_BYTES` | 否 | 262144 | 请求体上限 |
| `ASSISTANT_MAX_CATALOG` | 否 | 100 | 目录条数上限 |
| `ASSISTANT_DEBUG` | 否 | `0` | `1` 时开放 `/docs`、`/redoc`、`/openapi.json` |

缺少 `BASE_URL` / `MODEL` / `API_KEY` 不会导致启动失败，解析时返回 `DEGRADED + DISABLED`，没有模型配置也能跑通整条链路。

## 本地运行

```bash
pip install -r requirements-dev.txt
ASSISTANT_INTERNAL_TOKEN=dev-token uvicorn app.main:app --port 8092
pytest -q
```

测试不访问真实模型：`_call` 被 monkeypatch，覆盖正常 JSON、非法 JSON、超时、上游 5xx、缺配置与越权 token。

## 日志与隐私

只记录 `requestId`、用户 id、文本长度与 `sha256` 前 8 位、目录条数、耗时和 `reason`；**不记录用户原文、token、api-key**。文本仍会发送给模型供应商，页面上有固定提示。
