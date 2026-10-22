# 简易订单系统 · 订单/支付微服务拆分设计

| 项目 | 内容 |
|---|---|
| 版本 | v1.0（2026-10-22，待评审） |
| 状态 | 关键选型已确认，实施计划待评审 |
| 配套文档 | [微服务实施计划](microservices-plan.md)、[单体设计方案](design.md) |
| 基线 | v1.0 单体，M0–M8 已完成并验收 |

## 1. 背景与目标

当前系统是模块化单体：`catalog`、`ordering`、`payment`、`user` 在同一个 Spring Boot 应用、同一个 H2 文件中，支付模块通过进程内的 `OrderPaymentPort` 访问订单数据，并在同一个本地事务中完成“创建支付单 + 标记订单已支付”。

本期将**订单/支付**拆成两个独立部署的微服务：

- `na-order-service`：用户、商品、订单、库存、页面、公开 API、支付编排；
- `na-payment-service`：支付单生命周期、Mock 渠道、退款，仅内网访问。

### 1.1 目标

- 两个服务独立构建、独立部署、独立数据库；
- 浏览器访问的公开 API 与页面行为保持不变；
- 用 PostgreSQL 16 替代 H2，数据库按服务隔离；
- 用同步 Saga + 补偿 + 对账替代跨服务本地事务；
- 保留将来拆分商品服务、接入真实支付渠道、引入 MQ 的边界。

### 1.2 非目标（本期不做）

- 不引入 RabbitMQ/Kafka、Outbox、事件驱动；
- 不引入 Eureka/Consul、Spring Cloud Gateway、Feign；
- 不拆分用户、商品、库存；
- 不接入真实支付渠道，不实现财务对账；
- 不迁移旧订单/支付数据（旧数据删除）；
- 不做多实例部署、分布式锁、ShedLock；
- 不做 mTLS/OAuth2，内部调用使用共享 token。

## 2. 已确认决策

| # | 决策 | 结果 |
|---|---|---|
| 1 | 服务数量 | 2 个：订单服务 + 支付服务 |
| 2 | 命名前缀 | `na-` |
| 3 | 数据库 | PostgreSQL 16，一个实例，两个 database / 两个账号 |
| 4 | 一致性模式 | 同步 Saga + 补偿 + 对账（最终一致，正常路径即时返回） |
| 5 | 旧数据 | 删除，不迁移；新库初始化并 seed |
| 6 | 入口 | 仅订单服务对宿主暴露 8081；支付服务仅内网 |
| 7 | 未引入组件 | 无 MQ、无注册中心、无网关、无 Feign |

## 3. 命名与模块

`na-` 前缀应用于 Maven 模块、Docker 服务/镜像、数据库、数据库账号、Java 基础包名和日志中的服务名。若只需覆盖其中部分层级，实施前可调整，但同一层级必须保持一致。

| 层级 | 名称 |
|---|---|
| Maven 父工程 | `na-order-platform`（groupId `com.example.na`，version `2.0.0`） |
| 契约模块 | `na-payment-contract` |
| 订单服务模块 / 镜像 | `na-order-service` / `na-order-service:local` |
| 支付服务模块 / 镜像 | `na-payment-service` / `na-payment-service:local` |
| Compose 服务 | `na-postgres`、`na-order-service`、`na-payment-service` |
| Spring 应用名 | `na-order-service`、`na-payment-service` |
| 订单 Java 包 | `com.example.na.order` |
| 支付 Java 包 | `com.example.na.payment` |
| 契约 Java 包 | `com.example.na.payment.contract` |
| 数据库 | `na_orderdb`、`na_paymentdb` |
| 数据库账号 | `na_order_user`、`na_payment_user` |
| PostgreSQL 卷 | `na-pg-data` |
| 宿主访问 | 订单 `8081`；PostgreSQL 调试 `127.0.0.1:5433`（可选） |
| 内网端口 | 订单 `8080`、支付 `8080`、PostgreSQL `5432` |

### 3.1 仓库结构

```text
demo/
  pom.xml                          # na-order-platform 父 POM
  na-payment-contract/             # 纯 DTO、API 枚举、请求头/错误码常量
  na-order-service/
    pom.xml
    src/main/java/com/example/na/order/
      catalog/                     # 商品（保留）
      ordering/                    # 订单、库存、Saga 日志、编排
      user/                        # 登录用户（保留）
      paymentclient/               # 支付服务 HTTP 客户端与配置
      config/                      # 安全、种子数据、页面路由、OpenAPI
      shared/                      # 业务异常、统一错误响应、分页
    src/main/resources/
      templates/ static/           # 现有页面与静态资源
      db/migration/                # Flyway：orderdb
      application.yml
      application-docker.yml
      application-test.yml
    Dockerfile
  na-payment-service/
    pom.xml
    src/main/java/com/example/na/payment/
      domain/ repository/ service/ channel/ web/ config/ shared/
    src/main/resources/
      db/migration/                # Flyway：paymentdb
      application.yml
      application-docker.yml
      application-test.yml
    Dockerfile
  scripts/
    deploy.cmd
    deploy.sh
    backup.sh
    postgres/init-databases.sh
  docker-compose.yml
  .env.example
```

### 3.2 端口

| 服务 | 容器端口 | 宿主机 | 说明 |
|---|---|---|---|
| `na-order-service` | 8080 | `8081` | 页面、公开 API、Swagger |
| `na-payment-service` | 8080 | 不映射 | 仅 Docker 内网；本地调试可临时映射 `127.0.0.1:8082` |
| `na-postgres` | 5432 | `127.0.0.1:5433`（可选） | 本地调试；默认不暴露更安全 |

## 4. 目标架构

```text
                         浏览器（Session）
                                │
                                ▼
                   na-order-service :8081
                   ├─ 登录 / 商品 / 订单 / 库存 / 页面
                   ├─ 公开 API（BFF，含 /api/payments）
                   ├─ 支付编排（Saga Orchestrator）
                   ├─ 对账任务
                   └─ na_orderdb
                        users / products / orders / order_items
                        order_payment_attempt
                                │
                                │ HTTP /internal/v1/payments
                                │ X-Service-Token + X-Request-Id
                                ▼
                   na-payment-service :8080（仅内网）
                   ├─ 支付单 / Mock 渠道 / 退款 / 关闭
                   └─ na_paymentdb
                        payments
                                │
                                ▼
                   na-postgres :5432（同一实例，两个 database）
                   ├─ na_orderdb   ← na_order_user
                   └─ na_paymentdb ← na_payment_user
```

### 4.1 职责边界

| 服务 | 负责 | 不负责 |
|---|---|---|
| `na-order-service` | 用户登录、商品、订单、库存、页面、公开 API、支付编排、Saga 日志、对账 | 支付单状态、渠道调用、退款执行 |
| `na-payment-service` | 支付单、Mock 渠道、退款、关闭未扣款支付单、支付查询 | 订单状态、用户登录、商品、库存、订单金额计算 |

### 4.2 依赖方向必须反转

当前 [OrderPaymentPort.java:5](../src/main/java/com/example/order/ordering/api/OrderPaymentPort.java#L5) 的方向是 `payment → ordering`：

```java
PayableOrder loadForPayment(String orderNo, Long userId);
boolean markPaid(String orderNo, String paymentNo, LocalDateTime paidAt);
```

单体里它让支付模块不直接依赖订单实体，是合理的。但拆成服务后，如果继续让支付服务通过 HTTP 调用订单服务：

- 支付服务会依赖订单状态机，无法作为通用支付能力复用；
- 支付服务的本地事务无法覆盖远程订单更新；
- 订单领域知识泄漏到支付服务，边界反而更差。

目标方向是 **order-service → payment-service**：

- 订单服务负责业务编排、校验订单是否可支付、标记订单已支付；
- 支付服务只接收 `orderNo / userId / amount / currency`，不认识订单状态；
- 支付服务可以在将来被其他业务复用；
- 订单状态机仍完整留在订单服务。

## 5. 数据架构

### 5.1 PostgreSQL 拓扑

- 一个 `postgres:16` 容器，实例内创建两个 database；
- `na_order_user` 只拥有 `na_orderdb`，`na_payment_user` 只拥有 `na_paymentdb`；
- 撤销 `PUBLIC` 对两个 database 的 CONNECT 权限，避免互相访问；
- 两个服务使用独立 JDBC URL、独立账号、独立 Flyway 迁移目录；
- 不做跨库外键、跨库 JOIN、跨库事务；
- 演示环境共用实例以节省资源；生产环境应拆成两个实例或集群，隔离故障域。

初始化脚本 `scripts/postgres/init-databases.sh` 使用 `.env` 中的账号密码创建：

```sql
CREATE ROLE na_order_user LOGIN PASSWORD '...';
CREATE DATABASE na_orderdb OWNER na_order_user;
REVOKE ALL ON DATABASE na_orderdb FROM PUBLIC;
GRANT CONNECT ON DATABASE na_orderdb TO na_order_user;

CREATE ROLE na_payment_user LOGIN PASSWORD '...';
CREATE DATABASE na_paymentdb OWNER na_payment_user;
REVOKE ALL ON DATABASE na_paymentdb FROM PUBLIC;
GRANT CONNECT ON DATABASE na_paymentdb TO na_payment_user;
```

### 5.2 `na_orderdb`

| 表 | 说明 |
|---|---|
| `users` | 登录用户，沿用现有结构 |
| `products` | 商品、价格、库存、上下架，沿用现有结构 |
| `orders` | 订单主表；状态仍为 `PENDING_PAYMENT / PAID / CANCELLED` |
| `order_items` | 订单明细，唯一 JPA 级联 `order_items → orders` |
| `order_payment_attempt` | 支付 Saga 日志，订单服务独有，不对支付服务共享 |

`orders` 不新增 `payment_no`：支付单号保存在 `order_payment_attempt`，避免在两个服务间重复维护支付状态。订单详情页当前不展示支付单号，公开响应无需变化。

`order_payment_attempt` 建议结构：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint identity | 主键 |
| `order_no` | varchar(32) unique | 业务关联键，一个订单一条 |
| `user_id` | bigint | 发起支付的用户 |
| `amount` | numeric(12,2) | 下单时订单总额快照，用于校验支付响应 |
| `status` | varchar(20) | `STARTED / SUCCEEDED / FAILED / REFUND_PENDING / REFUNDED` |
| `payment_no` | varchar(32) | 支付服务返回的支付单号，可为空 |
| `attempt_count` | int | 发起/对账重试次数 |
| `last_error` | varchar(255) | 最近一次失败原因，便于排查 |
| `created_at` | timestamp | 创建时间 |
| `updated_at` | timestamp | 最近状态变更时间 |

索引：`unique(order_no)`、`(status, updated_at)`。

### 5.3 `na_paymentdb`

`payments` 沿用现有字段并补充：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigint identity | 主键 |
| `payment_no` | varchar(32) unique | 支付单号，渠道幂等键 |
| `order_no` | varchar(32) unique | 一个订单只允许一笔支付单 |
| `user_id` | bigint | 用户引用，无跨库外键 |
| `amount` | numeric(12,2) | 支付金额 |
| `currency` | varchar(3) | 本期固定 `CNY` |
| `channel` | varchar(20) | 本期固定 `MOCK` |
| `status` | varchar(20) | `CREATED / SUCCEEDED / FAILED / REFUNDED` |
| `created_at` | timestamp | 创建时间 |
| `succeeded_at` | timestamp | 支付成功时间 |
| `refunded_at` | timestamp | 退款时间 |
| `refund_reason` | varchar(255) | 退款原因 |

索引：`unique(payment_no)`、`unique(order_no)`、`(user_id, created_at)`。

### 5.4 状态模型

**订单**：`PENDING_PAYMENT → PAID`；`PENDING_PAYMENT → CANCELLED`。不增加 `PAYING` 状态，跨服务竞争用条件更新 + 补偿解决。

**支付**：`CREATED → SUCCEEDED`；`CREATED → FAILED`（关闭/渠道拒绝）；`SUCCEEDED → REFUNDED`。`FAILED`、`REFUNDED` 为终态。

**Saga 日志**：

```text
STARTED ──► SUCCEEDED
   │
   ├──► FAILED ──► STARTED（用户重试）
   │
   └──► REFUND_PENDING ──► REFUNDED
```

### 5.5 数据初始化与旧数据

- 旧 H2 数据删除，不迁移；
- 新库由 Flyway 创建 schema，`DataInitializer` 继续负责幂等 seed 商品和演示账号；
- 旧镜像与旧 `demo_order-data` 卷保留到验收通过，作为回滚快照，之后可删除；
- 旧 paid 订单和支付历史不再可查。

### 5.6 表结构管理

- 两个服务分别引入 Flyway，迁移目录为各自的 `classpath:db/migration`；
- Spring Boot 2.7.18 默认的 Flyway 8.5.x 与 PostgreSQL JDBC 42.3.x 对 PostgreSQL 16 支持不足，父 POM 显式固定 Flyway 9.22.x 与 JDBC 42.7.x（均为 Java 8 兼容版本）；
- M1 启动时验证 PG 16 连接与迁移；若仍不兼容，降级基础镜像为 PostgreSQL 15，应用代码不变；
- 运行时 `spring.jpa.hibernate.ddl-auto=validate`，避免 Hibernate 自动改表；
- 测试使用 H2 PostgreSQL 兼容模式，尽量复用同一套 Flyway 脚本；若 H2 与 PG 语法冲突，调整脚本而不是维护两套 schema；
- 真实 PostgreSQL 上的行为由部署后的 E2E 验证。

## 6. 内部 API

### 6.1 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/v1/payments` | 创建并执行支付；按 `orderNo` 幂等，可恢复 `CREATED` |
| GET | `/internal/v1/payments/{paymentNo}?userId=` | 查询支付单，校验 userId |
| GET | `/internal/v1/payments/by-order/{orderNo}?userId=` | 按订单查询支付单，供订单侧对账 |
| POST | `/internal/v1/payments/{paymentNo}/refund` | 退款；`SUCCEEDED → REFUNDED`，幂等 |
| POST | `/internal/v1/payments/{paymentNo}/close` | 关闭未扣款支付单；`CREATED → FAILED`，幂等 |

### 6.2 请求头

| 头 | 说明 |
|---|---|
| `X-Service-Token` | 服务间共享 token，订单/支付两侧从环境变量读取 |
| `X-Request-Id` | 请求链路 ID，订单服务生成或透传，支付服务记录到日志 |
| `Content-Type` | `application/json` |

支付服务不暴露公网端口，内部 token 是第一道认证，Docker 私网是第二道隔离。token 使用常量时间比较。

### 6.3 创建支付请求

```json
{
  "orderNo": "ORD20261022120000123456",
  "userId": 1,
  "amount": 557.00,
  "currency": "CNY"
}
```

响应：

```json
{
  "paymentNo": "PAY20261022120000567890",
  "orderNo": "ORD20261022120000123456",
  "userId": 1,
  "amount": 557.00,
  "currency": "CNY",
  "channel": "MOCK",
  "status": "SUCCEEDED",
  "createdAt": "2026-10-22T12:00:00",
  "succeededAt": "2026-10-22T12:00:00",
  "refundedAt": null
}
```

### 6.4 幂等语义

- `order_no` 唯一：一个订单最多一笔支付单；
- 重复 POST 且已有 `SUCCEEDED`：返回 200 + 同一支付单，不重复扣款；
- 已有 `CREATED`：校验 `userId / amount / currency` 一致后，按 `paymentNo` 幂等恢复渠道调用，成功后返回 200；
- 已有 `FAILED`：返回 409，由订单服务记录失败；
- 已有 `REFUNDED`：返回 409，不允许再次支付；
- 同一 `orderNo` 但金额、用户不一致：返回 409，视为幂等键冲突；
- 退款：已 `REFUNDED` 直接返回 200；`SUCCEEDED` 才执行退款；
- 关闭：已 `FAILED`/`REFUNDED` 直接返回；`SUCCEEDED` 返回 409（应走退款）。

### 6.5 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 参数缺失、金额非正、币种不支持 |
| 401 | `UNAUTHORIZED_INTERNAL` | token 缺失或错误 |
| 404 | `PAYMENT_NOT_FOUND` | 支付单不存在或不属于该用户 |
| 409 | `PAYMENT_IN_PROGRESS` | 支付单正在处理中 |
| 409 | `PAYMENT_CONFLICT` | 幂等键冲突、状态不允许 |
| 409 | `REFUND_NOT_ALLOWED` | 当前状态不可退款 |
| 500 | `PAYMENT_INTERNAL_ERROR` | 支付服务内部错误 |

支付服务返回统一错误体：

```json
{ "code": "PAYMENT_CONFLICT", "message": "支付单已存在，请刷新后重试" }
```

订单服务负责把内部错误映射为现有公开错误格式 `{ "message": "..." }`，前端无需感知内部错误码。

### 6.6 超时

订单服务的支付客户端显式设置：

- 连接超时 2 秒；
- 读取超时 5 秒；
- 不自动重试 `POST /payments`，由用户重试或对账恢复，避免放大重复请求。

## 7. 公开 API 与 BFF

浏览器只访问订单服务，公开 API 保持不变：

| 方法 | 路径 | 行为 |
|---|---|---|
| POST | `/api/payments` | 订单服务校验当前用户和订单归属，转调支付服务 |
| GET | `/api/payments/{paymentNo}` | 订单服务携带当前 userId 查询支付服务 |

响应字段保持 `paymentNo / orderNo / amount / channel / status / succeededAt`，首次创建 201，重复支付 200，冲突 409。前端 `orders.js`、`order-detail.js` 不需要修改。

BFF 职责：

- 认证与用户隔离；
- 订单归属校验（404 不泄露他人订单）；
- 内部错误到公开错误格式的映射；
- 支付服务不可用时返回 503，订单保持 `PENDING_PAYMENT`。

## 8. 支付 Saga

### 8.1 正常路径

```text
order-service                         payment-service                 Mock 渠道
     │                                       │                           │
     │ 1. 写 attempt STARTED                │                           │
     │    （na_orderdb 本地事务）            │                           │
     │── 2. POST /internal/v1/payments ─────►│                           │
     │                                       │ 3. 创建 payment CREATED   │
     │                                       │    （paymentdb 事务 1）    │
     │                                       │── 4. 渠道扣款 ───────────►│
     │                                       │◄── 扣款成功 ──────────────│
     │                                       │ 5. payment SUCCEEDED      │
     │                                       │    （paymentdb 事务 2）    │
     │◄── 6. 200 SUCCEEDED ──────────────────│                           │
     │ 7. order PAID + attempt SUCCEEDED      │                           │
     │    （na_orderdb 本地事务）             │                           │
```

### 8.2 本地事务边界

| 步骤 | 执行方 | 事务边界 | 说明 |
|---|---|---|---|
| 1 | order-service | `na_orderdb` 短事务 | 插入/重置 attempt 为 `STARTED` |
| 2 | order-service | 无事务 | HTTP 调用，不能包在 `@Transactional` 中 |
| 3 | payment-service | `na_paymentdb` 事务 1 | 插入 `CREATED` 并提交 |
| 4 | payment-service | 无事务 | 调渠道，`paymentNo` 为幂等键 |
| 5 | payment-service | `na_paymentdb` 事务 2 | `CREATED → SUCCEEDED` |
| 6 | order-service | 无事务 | 校验响应 |
| 7 | order-service | `na_orderdb` 短事务 | `PENDING_PAYMENT → PAID` 与 attempt `SUCCEEDED` 同一事务 |

硬性约束：

- 远程调用前后都不能持有数据库事务；
- 订单 PAID 与 attempt SUCCEEDED 必须同一本地事务，否则会多一种不一致；
- 订单取消与库存回补必须同一本地事务；
- 支付服务创建 `CREATED` 与标记 `SUCCEEDED` 分开提交，避免渠道超时拖垮事务。

### 8.3 分界点

渠道扣款成功是整个 Saga 的分界点：

- **扣款前**：可以中止。关闭支付单、attempt 标记 `FAILED`，订单保持待支付；
- **扣款后**：不能撤销。必须继续把订单标记 `PAID`；如果订单已经取消，则退款并标记 `REFUNDED`。

### 8.4 取消与支付竞争

```text
T1  order-service 读到订单 PENDING_PAYMENT
T2  order-service 写 attempt STARTED
T3  payment-service 扣款成功，payment SUCCEEDED
T4  用户取消订单成功：order CANCELLED，库存已回补
T5  order-service markPaid：WHERE status='PENDING_PAYMENT' 更新 0 行
T6  order-service 重新加载订单，发现 CANCELLED
    → attempt REFUND_PENDING
    → 调用退款
    → payment SUCCEEDED → REFUNDED
    → attempt REFUNDED
    → 返回 409「订单已取消，支付已退款」
```

最终状态：订单 `CANCELLED`、库存已回补、支付 `REFUNDED`。

反向竞争：`markPaid` 先成功，取消的条件更新影响 0 行，返回 409；最终订单 `PAID`、支付 `SUCCEEDED`、库存保持扣减。

不引入分布式锁，**条件更新决定胜负，失败方补偿或返回冲突**。

### 8.5 失败分类

| 失败 | 判定 | 处理 |
|---|---|---|
| 连接拒绝 / 4xx 业务错误 | 请求未成功或明确失败 | attempt `FAILED`，返回 503/409 |
| 读取超时 / 5xx / 网络中断 | 结果不确定 | attempt 保持 `STARTED`，返回 503，交给对账 |
| 支付成功但订单更新失败 | 订单被取消或 DB 异常 | 取消则退款；DB 异常则对账补做 |
| 订单已 PAID 时重试 | 幂等命中 | 查询支付单，返回 200 同一支付单 |
| 订单已 CANCELLED 时重试 | 状态冲突 | 返回 409，不发起新支付 |

## 9. 补偿

### 9.1 正向动作与补偿

| 正向动作 | 补偿动作 | 幂等保证 |
|---|---|---|
| 创建支付单 `CREATED`（未扣款） | 关闭支付单，`CREATED → FAILED` | `order_no` 唯一 + 条件状态更新 |
| 渠道扣款成功 | 退款，`SUCCEEDED → REFUNDED` | `paymentNo` 渠道幂等键 + 退款接口幂等 |
| 订单标记 `PAID` | 不回滚为待支付；若订单已取消，走退款 | 条件更新；不复活已取消订单 |
| 下单扣库存 | 取消订单时回补库存 | 取消条件更新保证只回补一次 |
| 取消订单 + 回补库存 | 无需补偿，本地事务原子提交 | 本地事务 |

### 9.2 补偿原则

1. **补偿不是回滚**：退款是新的业务动作，保留退款时间、原因和审计记录；
2. **补偿必须幂等**：对账可能重复调用退款/关闭；
3. **补偿需要中间状态**：订单侧用 `REFUND_PENDING` 表示“退款已发起、结果未确认”，失败后由对账重试；
4. **只对已提交的动作补偿**：未扣款的 `CREATED` 用关闭，不用退款；
5. **补偿不能复活业务**：支付成功但订单已取消，最终保持 CANCELLED + REFUNDED，不把订单改回待支付。

## 10. 对账

### 10.1 为什么用 Saga 日志

只靠订单状态无法区分：

- 用户从未发起支付；
- 用户发起支付，支付成功，但订单服务崩溃，未标记 PAID。

`order_payment_attempt` 记录支付编排意图和结果，对账只扫描“发起过支付但未闭环”的记录，避免盲扫所有待支付订单，也能追踪退款补偿。

### 10.2 对账任务

- 位置：`na-order-service`，`@Scheduled(fixedDelay = 60000)`；
- 每次取 `STARTED` / `REFUND_PENDING` 且 `updated_at < now-60s` 的记录，最多 100 条；
- 对每条调用支付服务 `GET /internal/v1/payments/by-order/{orderNo}?userId=`；
- 所有状态更新使用条件更新，重复执行安全；
- 单实例部署；多实例时引入 ShedLock。

### 10.3 对账决策表

| Saga 日志 | 支付状态 | 订单状态 | 动作 |
|---|---|---|---|
| STARTED | SUCCEEDED | PENDING_PAYMENT | 补 markPaid，attempt `SUCCEEDED` |
| STARTED | SUCCEEDED | CANCELLED | attempt `REFUND_PENDING` → 退款 → `REFUNDED` |
| STARTED | CREATED | PENDING_PAYMENT | 重试支付（paymentNo 幂等恢复） |
| STARTED | CREATED | CANCELLED | 关闭支付单，attempt `FAILED` |
| STARTED | 不存在 / FAILED | 任意 | attempt `FAILED`；订单仍 PENDING 时用户可重试 |
| STARTED | REFUNDED | CANCELLED | attempt `REFUNDED` |
| REFUND_PENDING | SUCCEEDED | CANCELLED | 调用退款，成功 → `REFUNDED` |
| REFUND_PENDING | REFUNDED | CANCELLED | attempt `REFUNDED` |
| REFUND_PENDING | 查询失败 | 任意 | 保留状态，下一轮重试；超过次数告警 |

伪代码：

```text
每 60 秒：
  attempts = findTop100ByStatusInAndUpdatedAtBefore(
      [STARTED, REFUND_PENDING], now - 60s)
  for attempt in attempts:
      payment = paymentClient.getByOrder(attempt.orderNo, attempt.userId)
      order = orderRepository.findByOrderNo(attempt.orderNo)

      if attempt.status == STARTED:
          if payment.status == SUCCEEDED:
              if order.status == PENDING_PAYMENT:
                  tx { order.markPaid(); attempt.succeed(); }
              else if order.status == CANCELLED:
                  tx { attempt.refundPending(); }
                  paymentClient.refund(payment.paymentNo, "ORDER_CANCELLED")
                  tx { attempt.refunded(); }
          else if payment.status == CREATED:
              if order.status == CANCELLED:
                  paymentClient.close(payment.paymentNo, "ORDER_CANCELLED")
                  tx { attempt.failed(); }
              else:
                  paymentClient.retryPay(attempt)
          else:
              tx { attempt.failed(); }

      if attempt.status == REFUND_PENDING:
          if payment.status == SUCCEEDED:
              paymentClient.refund(payment.paymentNo, "ORDER_CANCELLED")
          if payment.status == REFUNDED:
              tx { attempt.refunded(); }
```

### 10.4 重试与告警

- `attempt_count` 每次对账递增；
- 连续失败超过阈值（例如 10 次）停止自动重试并记录错误日志，等待人工处理；
- 不因“支付状态未知”自动退款，必须先查询渠道/支付状态；
- Mock 渠道结果确定，生产渠道需实现 `query` 能力后再启用自动恢复。

### 10.5 对账范围

本期对账是**服务间状态对账**：

- 比较 `na_orderdb.order_payment_attempt / orders` 与 `na_paymentdb.payments`；
- 修复内部状态不一致。

不包含**财务对账**：

- 不在本期拿渠道结算文件逐笔核对；
- 真实渠道接入后，需要 T+1 渠道账单、长款/短款处理、人工复核。

## 11. 安全

| 边界 | 方案 |
|---|---|
| 浏览器 → 订单服务 | 现有 Session 表单登录、BCrypt、CSRF 策略不变 |
| 订单服务 → 支付服务 | `X-Service-Token` + Docker 私网；支付服务不映射宿主端口 |
| 支付服务自身 | 无表单登录、无 Session、无 CSRF；只接受内部 token |
| 用户隔离 | 公开接口按 Session userId 校验；内部查询同时校验 userId |
| Swagger | 仅订单服务暴露；支付服务在 docker profile 关闭 springdoc |
| 密钥 | `.env` 保存数据库密码和内部 token，`.gitignore` 排除；提供 `.env.example` |
| 管理端点 | 只暴露 `health`；支付服务健康检查仅内网 |

生产化升级：

- 内部 token 换 mTLS 或 OAuth2 Client Credentials；
- 支付服务置于独立网络策略；
- 接入密钥管理服务；
- 公开 API 若开放给第三方，改为 Token/JWT 与显式 CSRF 方案。

## 12. 部署

### 12.1 Compose 拓扑

```yaml
services:
  na-postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: na_admin
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - na-pg-data:/var/lib/postgresql/data
      - ./scripts/postgres/init-databases.sh:/docker-entrypoint-initdb.d/init-databases.sh:ro
    ports:
      - "127.0.0.1:5433:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U na_admin -d postgres"]
      interval: 10s
      timeout: 3s
      retries: 10

  na-payment-service:
    build:
      context: .
      dockerfile: na-payment-service/Dockerfile
    image: na-payment-service:local
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://na-postgres:5432/na_paymentdb
      SPRING_DATASOURCE_USERNAME: na_payment_user
      SPRING_DATASOURCE_PASSWORD: ${NA_PAYMENT_DB_PASSWORD}
      APP_INTERNAL_TOKEN: ${INTERNAL_SERVICE_TOKEN}
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx256m -Duser.timezone=Asia/Shanghai"
    expose:
      - "8080"
    depends_on:
      na-postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 10
      start_period: 30s
    mem_limit: 768m

  na-order-service:
    build:
      context: .
      dockerfile: na-order-service/Dockerfile
    image: na-order-service:local
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://na-postgres:5432/na_orderdb
      SPRING_DATASOURCE_USERNAME: na_order_user
      SPRING_DATASOURCE_PASSWORD: ${NA_ORDER_DB_PASSWORD}
      APP_PAYMENT_BASE_URL: http://na-payment-service:8080
      APP_PAYMENT_SERVICE_TOKEN: ${INTERNAL_SERVICE_TOKEN}
      APP_PAYMENT_CONNECT_TIMEOUT_MS: "2000"
      APP_PAYMENT_READ_TIMEOUT_MS: "5000"
      APP_RECONCILIATION_ENABLED: "true"
      TZ: Asia/Shanghai
      JAVA_TOOL_OPTIONS: "-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai"
    ports:
      - "8081:8080"
    depends_on:
      na-postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 10
      start_period: 30s
    mem_limit: 1g

volumes:
  na-pg-data:
```

说明：

- 订单服务只依赖 PostgreSQL 启动，不依赖支付服务：支付服务故障或未就绪时，浏览、下单、订单查询仍可用，支付接口返回 503；
- 支付服务与订单服务同时启动，首次部署时支付接口可能在支付服务就绪前短暂返回 503，由用户重试或对账兜底；
- 支付服务不映射宿主机端口；
- PostgreSQL 默认只映射到 `127.0.0.1:5433`，方便本地调试；生产不暴露；
- Flyway 在各自服务启动时执行；
- 资源限制与 JVM 参数匹配，避免容器 OOM。

### 12.2 一键部署

- Windows 双击 `scripts\deploy.cmd`；
- WSL 执行 `scripts/deploy.sh`；
- `deploy.sh` 负责：
  1. 检查 Docker / Compose / 8081 端口；
  2. 首次运行生成 `.env` 中的数据库密码和内部 token（已存在则复用）；
  3. `docker compose up -d --build --wait --wait-timeout 600`；
  4. `docker compose ps` 展示状态；
- 构建阶段执行测试；测试失败不替换旧容器；
- 首次构建需要下载 PostgreSQL 驱动、Flyway 等依赖，耗时会长于当前版本。

### 12.3 备份与恢复

PostgreSQL 不再使用 H2 文件卷直接 tar，改用 `pg_dump`：

```bash
# 停应用，保证两个库一致
docker compose stop na-order-service na-payment-service
docker compose exec -T na-postgres pg_dump -U na_order_user -d na_orderdb -Fc \
  > backups/na-orderdb-$(date +%Y%m%d-%H%M%S).dump
docker compose exec -T na-postgres pg_dump -U na_payment_user -d na_paymentdb -Fc \
  > backups/na-paymentdb-$(date +%Y%m%d-%H%M%S).dump
docker compose start na-order-service na-payment-service
```

恢复：

- 停止两个应用；
- 用 `pg_restore` 分别恢复到两个 database；
- 启动应用，验证订单与支付状态一致。

数据库卷 `na-pg-data` 不是唯一备份手段；生产应同时做定期 `pg_dump` 和卷/快照备份。

### 12.4 回滚

- 保留旧单体镜像和旧 `demo_order-data` 卷；
- 新栈使用新端口/新库，验收通过前不删除旧数据；
- 切流后新产生的订单/支付若要回滚，旧 H2 库无法自动获得这些数据；回滚应在低峰期进行，并接受新数据丢失或单独导出。

### 12.5 可观测性

- 订单服务在每次出站调用时**新生成** `X-Request-Id`（`PaymentClient.exchange`），支付服务在日志中打印该 `requestId`；入站请求头目前**未被读取、未做透传**，也未接入 MDC，跨服务串联仍依赖时间戳；
- 两个服务只暴露 `health`；支付服务侧日志打印 `requestId / orderNo / paymentNo`，订单服务侧业务日志打印 `orderNo / paymentNo`，不含金额以外的敏感信息；
- 关键状态变更记录 INFO 日志；冲突、对账失败记录 WARN/ERROR；
- 不记录 token、数据库密码；
- 后续可接入 Prometheus/Micrometer、集中日志，本期不做。

## 13. 测试策略

### 13.1 分层

| 层 | 方式 | 说明 |
|---|---|---|
| 单元测试 | JUnit 5 + Mockito | 领域状态、Saga 决策、错误映射 |
| 服务内集成 | `@SpringBootTest` + H2（PostgreSQL 模式） | 订单/支付各自的 Repository、事务、并发 |
| 契约测试 | `MockRestServiceServer` | 固定支付服务响应，验证订单客户端解析和错误映射 |
| 真实 PostgreSQL E2E | 部署后浏览器/API 脚本 | 验证 Flyway、唯一约束、条件更新、跨服务流程 |
| 故障注入 | 停支付服务、模拟超时、手工改库 | 验证 503、PENDING、补偿、对账 |
| 回归 | 现有 15 项 + 新增 Saga 场景 | 确保原有页面/API 行为不变 |

H2 只用于快速自动化测试，不作为 PostgreSQL 方言的最终证明；真实 PG 上的行为由 E2E 覆盖。

### 13.2 测试矩阵

| 编号 | 场景 | 层 |
|---|---|---|
| T1 | 正常下单 | 订单服务 |
| T2 | 库存不足整单回滚 | 订单服务 |
| T3 | 取消订单回补库存 | 订单服务 |
| T4 | 重复取消不重复回补 | 订单服务 |
| T5 | 正常支付 | 支付服务 + 跨服务 |
| T6 | 顺序/并发重复支付幂等 | 支付服务 + 跨服务 |
| T7 | 非法状态流转 | 支付服务 |
| T8 | 订单/支付越权 404 | 订单/支付 |
| T9 | 并发抢购不超卖 | 订单服务 |
| T10 | 未登录 API 401 | 订单服务 |
| S1 | 内部 API 缺少/错误 token → 401 | 支付服务 |
| S2 | 支付服务停机 → 订单保持 PENDING，返回 503 | 跨服务 |
| S3 | 支付响应超时/丢失 → 对账补 PAID | 跨服务 |
| S4 | 支付与取消竞争 → 订单 CANCELLED、支付 REFUNDED | 跨服务 |
| S5 | 支付成功但订单未 markPaid → 对账补 PAID | 跨服务 |
| S6 | 取消成功但退款前崩溃 → 对账补退款 | 跨服务 |
| S7 | 重复退款/关闭幂等 | 支付服务 |
| S8 | 支付金额与订单不一致 → 不标记 PAID 并补偿 | 订单服务 |
| S9 | 两个服务重启 → 数据保留、状态一致 | 部署 |
| S10 | 数据库隔离：order 账号无法访问 paymentdb | 部署 |

### 13.3 验收 E2E

在真实 PostgreSQL + 两个容器上执行：

1. 登录、浏览商品、加购物车；
2. 下单、支付成功、订单列表/详情显示 PAID；
3. 重复支付返回同一支付单，不产生第二笔；
4. 取消未支付订单，库存回补；
5. 停支付服务后支付 → 503，订单仍 PENDING；恢复后重试成功；
6. 模拟支付成功后订单未更新 → 对账补 PAID；
7. 模拟取消后未退款 → 对账补 REFUNDED；
8. 重启两个服务，订单/支付数据保留；
9. 页面无控制台报错，移动端无横向溢出；
10. 备份/恢复后状态一致。

## 14. 实施阶段

| 阶段 | 内容 | 关键验收 |
|---|---|---|
| M0 | 基线冻结、命名确认、旧镜像/旧卷保留 | 可回滚 |
| M1 | 多模块骨架、`na-payment-contract`、PostgreSQL/Flyway、Dockerfile 骨架 | 两模块可构建，schema 可初始化 |
| M2 | 抽取 `na-payment-service`：支付单、内部 API、token、退款/关闭 | 支付服务独立启动，不依赖订单类 |
| M3 | 改造 `na-order-service`：支付客户端、Saga 编排、attempt、BFF、对账 | 公开 API 行为不变，编排测试通过 |
| M4 | 本地双服务联调（8090/8091 + PG 5433） | 正常/失败/竞争场景通过 |
| M5 | Compose 一键部署、备份、文档 | `deploy.cmd` 成功，两服务 healthy |
| M6 | 故障注入、并发、E2E、总验收 | 全部 DoD 通过 |

详细任务、文件清单和验收项见[微服务实施计划](microservices-plan.md)。

## 15. 风险与权衡

| 风险 | 影响 | 应对 |
|---|---|---|
| 单 PostgreSQL 实例共用故障域 | 数据库故障同时影响两服务 | 演示接受；生产拆两个实例 |
| 同步调用支付服务 | 支付服务故障时支付接口 503 | 订单保持 PENDING，重试/对账；不阻塞浏览下单 |
| 无 MQ | 无法事件驱动、削峰 | 本期按同步 Saga；后续可加 outbox + MQ |
| 内部 token 静态共享 | 泄露后内部接口可被调用 | 私网隔离、不暴露端口；生产换 mTLS/OAuth2 |
| 对账任务单实例 | 多实例重复执行 | 本期单实例；扩容前加 ShedLock |
| H2 测试与 PG 差异 | 自动化测试可能漏掉方言问题 | 同一套 Flyway 脚本 + 真实 PG E2E |
| Boot 2.7 默认依赖对 PG 16 支持不足 | Flyway 迁移失败或驱动不兼容 | 固定 Flyway 9.22.x + JDBC 42.7.x；M1 验证；失败降级 PG 15 |
| 渠道幂等依赖 | 真实渠道若不支持幂等键，重试可能重复扣款 | 本期 Mock；接真实渠道前确认其幂等能力 |
| 退款失败 | 订单已取消但钱未退 | `REFUND_PENDING` + 对账重试 + 告警 |
| 对账漏单 | 长期不一致 | 阈值、次数、告警；生产接入渠道对账文件 |
| 旧数据删除 | 历史订单/支付不可查 | 演示接受；旧卷保留至验收后 |
| 共享 PG 实例账号权限 | 配置错误可能跨库访问 | 撤销 PUBLIC CONNECT，独立账号；部署检查 |
| `na-` 命名范围 | 可能与现有约定不一致 | 本设计统一模块/镜像/服务/库/包；实施前可调整 |

## 16. 待确认假设

- `na-` 前缀按第 3 节覆盖 Maven 模块、镜像、Compose 服务、数据库、账号和 Java 包；若只需覆盖服务名，实施前调整文档与计划；
- 演示环境使用一个 PostgreSQL 实例；生产环境拆独立实例；
- 公开 API 路径和页面保持不变，前端无需修改；
- 旧数据删除，旧 H2 卷只作为回滚快照。

确认后进入[实施计划](microservices-plan.md)评审；计划通过后才开始修改代码。

## 17. 实现记录

> 记录时间：2026-10-22。M0–M6 全部完成，验收报告见[实施计划](microservices-plan.md) M6。

### 17.1 已落地结构

| 项 | 实现 |
| --- | --- |
| Maven 模块 | `na-payment-contract`、`na-order-service`、`na-payment-service`，父 POM `na-order-platform:2.0.0` |
| 订单服务 | `com.example.na.order`，保留登录、商品、订单、库存、页面与 Swagger，新增 `payment`（BFF + 编排）、`paymentclient`、`paymentattempt`、`reconciliation` |
| 支付服务 | `com.example.na.payment`，独立支付实体/Repository/Service/渠道/内部 API，无订单、用户、商品代码 |
| 数据库 | PostgreSQL 16，`na_orderdb`（`na_order_user`）、`na_paymentdb`（`na_payment_user`），Flyway 迁移；两账号无跨库权限 |
| Saga 日志 | `order_payment_attempt`，状态 `STARTED / SUCCEEDED / FAILED / REFUND_PENDING / REFUNDED`，`attempt_count` 从首次插入的 1 开始 |
| 事务边界 | 远程支付与渠道调用均在数据库事务外；订单落库使用短事务与条件更新 |
| 对账 | `OrderPaymentReconciliationJob` 按 §10.3 决策表处理 `STARTED` / `REFUND_PENDING`，支持停机重试与告警阈值 |
| 内部认证 | `X-Service-Token` 常量时间比较，`X-Request-Id` 透传；支付服务只暴露 `/internal/v1/payments` |
| 公开 API | 路径、请求/响应字段与单体版本兼容，前端未修改 |

### 17.2 部署结果

| 项 | 结果 |
| --- | --- |
| Compose 服务 | `na-postgres`、`na-payment-service`、`na-order-service` 全部 healthy |
| 宿主端口 | 订单 `8081 → 8080`；支付仅 `expose 8080`，无宿主端口；PostgreSQL `127.0.0.1:5433` 便于调试 |
| 镜像 | `na-order-service:local`、`na-payment-service:local`，多阶段构建、BuildKit Maven 缓存、构建阶段执行测试 |
| 测试 | 订单 32 + 支付 13 = 45 个测试通过；容器构建阶段同样执行 |
| E2E | 真实 PostgreSQL + Docker 栈上 23 项浏览器检查全部通过，控制台无错误；并发支付、10 并发抢购、取消竞争、故障恢复与对账全部通过 |
| 运维 | `scripts\deploy.cmd` 一键部署成功；`scripts/backup.sh` 备份与 `pg_restore` 恢复验证通过 |
| 回滚快照 | 旧镜像 `order-system:local`、旧 H2 卷 `demo_order-data` 保留；旧单体容器已停止；旧 `src/` 已删除，快照在 `backups/source-v1-20261022-112630.tar.gz` |

### 17.3 与设计的偏差

1. **Flyway / PG 16**：Flyway 9.22.3、PostgreSQL JDBC 42.7.4 在 PG 16 上迁移与运行正常；官方兼容列表未覆盖 PG 16，保留该版本并记录为已知偏差。
2. **补偿退款超时**：退款渠道耗时超过订单服务 5s 读超时（例如本地注入 7s 延迟）时，接口返回 503，attempt 暂留 `REFUND_PENDING`，随后由对账置为 `REFUNDED`；与设计中的“支付结果未知 + 对账兜底”一致。
3. **本地支付端口**：本地联调支付服务监听 `127.0.0.1:8091`；最终 Compose 不映射宿主端口，内部地址为 `http://na-payment-service:8080`。
4. **对账开关**：Compose 中 `APP_RECONCILIATION_ENABLED=true`，使用默认 60s 周期/陈旧阈值；本地故障演练通过环境变量缩短到 10s/5s，仅用于测试。
5. **`deploy.cmd` 输出语言**：Windows `cmd.exe` 对 UTF-8 多字节提示行存在解析问题，批处理输出改为 ASCII；`deploy.sh`、应用页面与文档仍为中文。
6. **旧 `src/` 删除**：M4 验收后删除，删除前已确认 `backups/source-v1-20261022-112630.tar.gz` 包含完整旧源码；删除后新模块 `mvn compile` 通过。

### 17.4 已知限制

- Mock 渠道同步成功，未实现真实渠道的异步回调、幂等键与渠道对账文件；
- 同步 Saga 无 MQ，极端网络分区下依赖对账收敛；
- 对账单实例运行，未加 ShedLock；
- 单 PostgreSQL 实例共用故障域；
- 内部 token 为静态共享密钥，依赖 Docker 私网隔离；
- 支付容器刚启动时存在极短连接就绪竞态，首次调用可能 503，用户重试或对账可收敛；
- 人为通过内部 API 创建金额不一致支付单后，支付服务按 `order_no` 唯一约束保留退款单，同一订单后续合法支付会 409；正常订单编排不会产生此数据。

M6 已完成全量构建、真实 PostgreSQL E2E、并发/故障/边界检查；详细结果见[实施计划](microservices-plan.md)的 M6 验收报告。

### 17.5 交付后架构审查与 P0 修复（2026-10-22）

交付后按架构分层、事务一致性、安全运维数据、测试质量四个维度做了系统性审查，其中两个 P0 已修复并验证：

**P0-1 失败的支付尝试永久无法恢复。** `OrderPaymentAttemptStore.resume()` 在把 `FAILED` 复位为 `STARTED` 时，条件更新的状态守卫误传 `STARTED_ONLY`，而该行状态实际是 `FAILED`，`where status in (STARTED)` 命中 0 行，复位从未发生；编排器随即抛「支付尝试已失败，请重新下单」，订单卡在 `PENDING_PAYMENT`、库存不回补。`markFailed` 的触发面很宽（任何 400、任何非 SUCCEEDED 响应、任何 404），因此一次瞬时误判即永久报废订单。守卫改为 `FAILED_ONLY`。回归用例：`OrderPaymentOrchestratorTest.failedAttemptIsRetriedByNextPayment`（在旧代码上会以「支付尝试已失败，请重新下单」失败）。

**P0-2 内部鉴权可被编码路径绕过。** `InternalTokenFilter.shouldNotFilter` 用 `request.getRequestURI().startsWith("/internal/")` 决定是否放行；`getRequestURI()` 返回未解码的原始路径，而容器路由使用解码、归一化后的路径，两者不一致即产生绕过面。已复现：无 token 的 `GET /%69nternal/v1/payments/by-order/{真实订单}?userId=1` 返回 200 并泄露他人支付单的金额与状态（正常路径返回 401）；`//internal/...`、`/./internal/...` 同理。支付服务未引入 Spring Security，该过滤器是唯一鉴权层。改为**默认拒绝**：只有精确匹配 `/actuator/health`、`/error` 的路径免鉴权，其余一律要求服务 token。回归用例：`InternalAuthBypassTest`（走真实内嵌 Tomcat，MockMvc 复现不了该解码差异；旧代码下 `%69nternal` 变体断言 401 会得到 404）。

验证结果：全量测试 45 → **49** 个通过；WSL 内重建镜像并 `--wait` 部署健康；容器内复测编码路径与双斜杠变体均返回 401、`/actuator/health` 仍为 200、带合法 token 的内部调用仍返回 200、宿主机 8081 页面正常。

审查中确认但仍**未修复**的遗留项（按优先级）：渠道调用无幂等键与 `PROCESSING` 中间态、`refund`/`close` 先读状态后调渠道且无 CAS 认领、`refund`/`close` 不校验 `userId`、对账 `SUCCEEDED` 分支不校验金额与用户、对账无分布式锁、`/api/**` 关闭 CSRF 而认证为会话 Cookie、状态枚举与 API 路径未下沉到契约模块、`PaymentClient` 丢弃响应体 `code`（401/403 被伪装成 503）、`backup.sh` 的 `trap` 早于预检、无优雅停机、容器以 root 运行、`.env` 与 `backups/*.dump` 权限过宽、45 个测试全部只跑 H2。
