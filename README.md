# 简易订单系统（微服务版）

一个可运行、可演示的下单系统：登录 → 浏览商品 → 购物车 → 下单 → 模拟支付 → 订单查询，包含库存原子扣减、防超卖、取消回补、支付补偿与对账。

系统由两个 Spring Boot 微服务、一个可选的 Python AI 服务和一个 PostgreSQL 实例组成：

| 服务 | 职责 | 对外端口 |
|---|---|---|
| `na-order-service` | 登录、商品、订单、库存、支付 BFF、Saga 编排、对账、AI 草稿编排 | 宿主 `8081` |
| `na-payment-service` | 支付单、Mock 渠道、退款/关闭、内部 API | 仅 Docker 内网 `8080` |
| `na-assistant-service` | 自然语言解析为购物车草稿（可选，OpenAI 兼容模型） | 仅 Docker 内网 `8092` |
| `na-postgres` | 一个 PostgreSQL 16 实例，`na_orderdb` / `na_paymentdb` 两库两账号 | `127.0.0.1:5433`（本地调试可选） |

技术栈：Java 8、Spring Boot 2.7.18、Spring Security、Spring Data JPA、Thymeleaf、原生 JavaScript、PostgreSQL 16、Flyway、springdoc-openapi、Python 3.11、FastAPI、pytest、Docker Compose。

## 架构与一致性

```text
浏览器 ──> na-order-service ──> na_orderdb
                │  X-Service-Token + X-Request-Id
                ├────────────> na-payment-service ──> na_paymentdb
                └────────────> na-assistant-service（可选）──> 第三方模型 API
```

- 支付采用**同步 Saga**：订单服务写 `order_payment_attempt` 日志 → 调用支付服务 → 支付服务先落 `CREATED`、调用渠道、再落 `SUCCEEDED` → 订单服务在短事务中 markPaid + 标记 attempt。
- 远程调用与渠道调用都在数据库事务之外；跨服务更新使用条件更新与幂等状态流转。
- 失败补偿：取消与支付竞争时自动退款；金额/归属不一致时退款；支付结果未知时保留 `STARTED`，由定时对账按决策表收敛（补 PAID、退款或关闭）。
- 两库无跨库查询、外键或事务；内部 token 使用常量时间比较，支付服务不映射宿主端口。
- AI 链路使用独立的 `ASSISTANT_SERVICE_TOKEN`，订单服务不 `depends_on` 它；模型不可用或未配置时，草稿接口仍返回 200，只是 `degraded=true` 并回落到本地规则结果。

详细设计见 [docs/microservices-design.md](docs/microservices-design.md)、[docs/ai-ordering-design.md](docs/ai-ordering-design.md)，实施与验收记录见 [docs/microservices-plan.md](docs/microservices-plan.md)。

## 功能

- 表单登录 / 退出（Session、BCrypt）
- 商品列表、库存与售罄状态
- 浏览器本地购物车，结算时以服务端价格与库存为准
- 多商品下单、服务端计价、库存快照
- 库存条件更新防超卖，失败整单回滚
- 订单列表（分页、状态筛选）、订单详情、取消订单并回补库存
- 独立支付服务与支付单，Mock 渠道同步成功，重复支付幂等
- 支付故障 503、取消/支付竞争退款、定时对账
- AI 自然语言下单草稿：规则层永远可用，模型可选补充；草稿可编辑、歧义候选可点选，确认后加入购物车
- AI 未配置/超时/返回异常时页面明确提示并降级为规则结果，不阻断下单
- 统一 JSON 错误响应、参数校验
- Swagger UI 接口文档

## 快速开始（Docker，推荐）

前提：WSL2 Ubuntu 中 Docker 与 Compose 插件可用；项目位于 `/mnt/e/zhaoo_workspace/idea_workspace/demo`。

Windows 双击 `scripts\deploy.cmd`，或在 WSL 中执行：

```bash
cd /mnt/e/zhaoo_workspace/idea_workspace/demo
bash scripts/deploy.sh
```

- 首次运行自动生成 `.env`（PostgreSQL 超级用户密码、两个业务库密码、内部服务 token）；已有 `.env` 则复用。
- 构建阶段在容器内执行两个 Java 服务的测试，测试失败不会替换正在运行的容器。
- 首次构建需下载依赖，耗时较长；后续有 BuildKit 缓存。
- AI 下单默认不调用模型（`APP_ASSISTANT_ENABLED=false`），只走本地规则层。要接入真实模型：在 `.env` 填写 `ASSISTANT_SERVICE_TOKEN`、`ASSISTANT_BASE_URL`、`ASSISTANT_MODEL`、`ASSISTANT_API_KEY`，把 `docker-compose.yml` 中订单服务的 `APP_ASSISTANT_ENABLED` 改为 `"true"`，再执行 `docker compose up -d na-order-service`。API key 由使用者自行填写，不提交、不在会话中传递。

访问：

- 应用：<http://localhost:8081>
- Swagger：<http://localhost:8081/swagger-ui.html>（先在同一浏览器登录）
- 演示账号：`demo` / `123456`

常用命令：

```bash
docker compose ps
docker compose logs -f na-order-service na-payment-service na-assistant-service
docker compose down            # 停止并删除容器，保留 na-pg-data 卷
```

## 本地开发（8090 / 8091）

```bash
# 1. 只启动 PostgreSQL
docker compose up -d na-postgres

# 2. 构建（Java 8，JAVA_HOME 必须指向 JDK）
JAVA_HOME=/path/to/jdk-1.8 ./mvnw clean package

# 3. 启动支付服务
set -a; . ./.env; set +a
SPRING_DATASOURCE_PASSWORD="$NA_PAYMENT_DB_PASSWORD" \
APP_INTERNAL_TOKEN="$INTERNAL_SERVICE_TOKEN" \
java -jar na-payment-service/target/na-payment-service.jar --server.port=8091

# 4. 另开终端启动订单服务
set -a; . ./.env; set +a
SPRING_DATASOURCE_PASSWORD="$NA_ORDER_DB_PASSWORD" \
APP_PAYMENT_SERVICE_TOKEN="$INTERNAL_SERVICE_TOKEN" \
APP_RECONCILIATION_ENABLED=true \
java -jar na-order-service/target/na-order-service.jar --server.port=8090
```

本地访问 <http://localhost:8090>。支付服务默认读取 `http://localhost:8091`，两库默认连接 `localhost:5433`。

需要联调 AI 服务时，按 [docs/ai-ordering-design.md](docs/ai-ordering-design.md) §7.4 用覆盖文件把 `8092` 映射到宿主，再以 `APP_ASSISTANT_ENABLED=true`、`APP_ASSISTANT_URL=http://localhost:8092`、`APP_ASSISTANT_TOKEN=...` 启动订单服务。

## 测试

```bash
./mvnw clean package
```

共 105 个 Java 测试（订单 89 + 支付 16）与 35 个 Python 测试。Java 测试使用 H2（PostgreSQL 兼容模式）与同一套 Flyway 脚本：

| 模块 / 测试类 | 数量 | 覆盖 |
|---|---|---|
| `OrderServiceTest` | 6 | 下单、库存不足回滚、取消回补、越权、并发不超卖 |
| `PaymentBffApiTest` | 4 | 201/200、支付不可用 503、已取消 409、未登录 401 |
| `OrderPaymentOrchestratorTest` | 8 | 成功、重复支付、不可用、取消竞争退款、金额异常退款、冲突补偿、越权、失败尝试可重试 |
| `OrderPaymentReconciliationJobTest` | 6 | S5 补 PAID、S6 退款、不可用保留、REFUND_PENDING 收敛、缺支付单、CREATED 续跑 |
| `PaymentClientTest` | 6 | 请求头/请求体、404/409/503 映射、按订单查询、退款 |
| `SecurityTest` | 3 | 未登录 API 401 JSON、页面重定向、登录权限 |
| `PaymentServiceTest` / `InternalPaymentApiTest` | 9 + 4 | 支付幂等、状态流转、内部 token 认证 |
| `InternalAuthBypassTest` | 3 | 真实 HTTP 下的鉴权绕过变体（编码路径、双斜杠、`.` 段）、健康检查仍可匿名访问 |
| `assistant/*Test` | 56 | 规则匹配、草稿编排与合并、降级映射、HTTP 客户端超时/不可达、跨语言契约、限流 |
| Python `na-assistant-service/tests` | 35 | 鉴权、请求校验、模型正常/超时/非法输出降级、请求体限制、契约、日志隐私 |

H2 仅用于快速测试；Flyway、唯一约束、条件更新与完整链路由真实 PostgreSQL + 浏览器 E2E 验证。

Python 测试无需本机 Python 工具链：

```bash
docker run --rm -v "$PWD":/build -w /build/na-assistant-service python:3.11-slim \
  sh -c "pip install -q -r requirements-dev.txt && python -m pytest -q"
```

### 端到端与故障演练（可重放）

自动化测试之外的验证资产已纳入版本控制，可在已部署的栈上重放。`tests/e2e/e2e.js` 覆盖登录、购物车、下单、支付、取消、分页，以及 AI 草稿、降级提示、候选点选与加入购物车（共 31 项检查）：

```bash
# 浏览器 E2E（需本机 headless Chrome）
E2E_BASE=http://localhost:8081 node tests/e2e/e2e.js

# 故障演练（WSL 内执行，需 .env 与运行中的 compose 栈）
bash tests/drill/m6-security.sh   # 内部 token、越权、跨库权限
bash tests/drill/m6-race.sh       # 支付与取消并发竞争
bash tests/drill/m6-mismatch.sh   # 金额不一致补偿退款
bash tests/drill/m6-s5-s6.sh      # 崩溃恢复与对账收敛
bash tests/drill/p01-failed-attempt-recovery.sh  # FAILED 支付尝试可被下一次支付复位
```

E2E 脚本假设数据库为初始种子状态（会创建订单并消耗库存）；在当前演示库上重放前，请使用一次性 Compose 栈，或先备份并重置数据。

`tests/drill/m6-*.yml` 是演练用的 compose 覆盖文件（拉长渠道延迟、缩短对账周期），用法：
`docker compose -f docker-compose.yml -f tests/drill/m6-override.yml up -d`。

## 接口文档

主要公开接口（均由订单服务提供，路径与单体版本保持一致）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/me` | 当前登录用户 |
| GET | `/api/products` | 商品列表 |
| GET | `/api/products/{id}` | 商品详情 |
| POST | `/api/orders` | 创建订单 |
| GET | `/api/orders` | 我的订单（`status`、`page`、`size`） |
| GET | `/api/orders/{orderNo}` | 订单详情 |
| POST | `/api/orders/{orderNo}/cancel` | 取消订单 |
| POST | `/api/payments` | 支付订单（Mock，同步成功） |
| GET | `/api/payments/{paymentNo}` | 支付单详情 |
| POST | `/api/assistant/drafts` | 自然语言生成购物车草稿（需登录，规则 + 可选模型） |

支付服务内部接口位于 `/internal/v1/payments`，需要 `X-Service-Token`；assistant 内部接口 `/internal/v1/assistant/parse` 使用独立的 `ASSISTANT_SERVICE_TOKEN`。两者都不对外暴露。

## 备份与恢复

一致性备份（脚本会先停两个应用，导出后自动恢复启动）：

```bash
bash scripts/backup.sh
ls -lh backups/
```

产物为 `na-orderdb-<时间戳>.dump`、`na-paymentdb-<时间戳>.dump`（PostgreSQL custom 格式）。

恢复示例（会覆盖当前两个库，执行前先确认）：

```bash
docker compose stop na-order-service na-payment-service
docker compose exec -T na-postgres pg_restore -U na_order_user -d na_orderdb \
  --clean --if-exists < backups/na-orderdb-<时间戳>.dump
docker compose exec -T na-postgres pg_restore -U na_payment_user -d na_paymentdb \
  --clean --if-exists < backups/na-paymentdb-<时间戳>.dump
docker compose start na-order-service na-payment-service
```

数据库卷 `na-pg-data` 不是唯一备份手段；生产应同时做定期 `pg_dump` 与卷/快照备份。

## 已知限制

- 支付渠道为 Mock，同步成功；接入真实渠道前需确认其幂等键与退款查询能力。
- 一个订单只允许一张支付单（`uk_payments_order_no`）。若因金额/归属校验失败已生成终态支付单（REFUNDED/FAILED），该订单后续支付会返回 409，需取消后重新下单；本期未实现订单过期与新建支付单能力。
- 同步 Saga 无 MQ，服务不可用依赖重试与对账收敛；极端情况下会出现短暂不一致。
- 对账任务按单实例设计，多实例部署前需加分布式锁（如 ShedLock）。
- 演示环境共用一个 PostgreSQL 实例，数据库故障会同时影响两个服务；生产应拆分实例。
- 内部认证为静态共享 token；生产应在私网隔离基础上替换为 mTLS/OAuth2。
- AI 下单默认不启用模型；启用后用户输入会发送给第三方模型服务（页面已提示），请勿输入个人信息。
- AI 规则层为包含式匹配，可能误判（如“显示器支架”命中“显示器”），MVP 依赖草稿可编辑与低置信度提示兜底，尚无向量检索。
- AI 限流（10 次/分钟/用户）与目录缓存均为单实例内存实现，多实例部署前需改为网关/Redis。
- 草稿不锁库存，结算时仍以服务端价格与库存为准，仍可能因库存不足失败。
- 旧单体镜像 `order-system:local` 与旧 H2 卷 `demo_order-data` 仍保留，但旧回滚容器已清理；如需回滚需按旧单体文档重新创建容器。
- 旧数据按约定未迁移，新库从空库初始化并写入演示数据。
