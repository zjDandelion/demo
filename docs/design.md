# 简易订单系统 · 设计方案

| 项目 | 内容 |
|---|---|
| 版本 | v1.0（2026-10-22，已完成） |
| 日期 | 2026-10-22 |
| 配套文档 | [实施计划](plan.md) |
| 微服务改造 | [微服务设计](microservices-design.md) · [微服务实施计划](microservices-plan.md) |
| 状态 | 单体版本已实施完成；微服务版本见左侧改造文档 |

## 1. 背景与目标

在空目录 `E:\zhaoo_workspace\idea_workspace\demo` 中实现一个可运行、可演示的简易下单系统，覆盖"登录 → 浏览商品 → 购物车 → 下单 → 支付 → 订单查询"的完整闭环，并具备库存扣减、防超卖、订单/支付状态流转能力。

系统采用**模块化单体**：当前单应用、单库部署，但订单与支付在代码、数据表、接口层面彻底分开，为将来拆分支付服务预留边界。

## 2. 范围

**包含**

- 账号登录 / 退出（Session）
- 商品列表与库存展示
- 购物车（浏览器本地）+ 多商品下单
- 服务端计价、库存原子扣减、防超卖
- 模拟支付（独立支付模块与支付单）
- 取消订单并回补库存
- 我的订单列表（状态筛选、分页）与订单详情
- 接口文档（Swagger UI）
- Docker Compose 部署（WSL2 Ubuntu）

**不包含**

- 真实支付渠道、退款、对账
- 商品后台管理
- 收货地址、优惠券、运费、发货/物流
- 注册、找回密码、角色权限
- 分布式事务、Redis、MQ、多实例水平扩容
- 生产级数据库（当前 H2 文件库仅用于演示）

## 3. 已确认决策

| 决策点 | 选择 | 原因 / 约束 |
|---|---|---|
| JDK | 8（本机 Oracle 1.8.0_401） | 本机仅有 JDK 8，用户选择不升级 |
| Spring Boot | 2.7.18 | JDK 8 下最后一个 2.7.x 版本；使用 `javax.*` |
| 开发构建 | Maven Wrapper | 本机无 `mvn`/`gradle`，Windows 开发用 `mvnw` |
| 数据库 | H2 文件库 `./data/orderdb` | 零安装，演示足够；后续可迁移 PostgreSQL/MySQL |
| 页面 | Thymeleaf 页面壳 + 原生 JS fetch | 单 jar 提供全部页面，不引入独立前端工程 |
| 支付 | 独立 `payment` 模块、独立支付单、独立 API | 下单与支付分离，为未来拆服务预留 |
| 部署 | WSL2 Docker 多阶段构建 + Compose 一键部署；宿主机 8081 → 容器 8080 | 不在 Windows 打包；已确认 Docker 29.6.2 + Buildx + Compose v5.3.1、systemd；8080 被现有容器占用 |
| CI | 本期不做；服务稳定后再评估 Jenkins | 避免个人演示阶段引入额外服务与权限复杂度 |
| 演示账号 | `demo / 123456`（可通过配置覆盖） | 仅演示使用，公网部署必须更换 |

## 4. 总体架构

```
Windows 浏览器
    │  http://localhost:8081（WSL2 转发，容器内 8080）
    ▼
WSL2 Ubuntu / Docker Compose
    │
Spring Boot 单应用（JRE 8）
    ├── Spring Security（Session + BCrypt）
    ├── Thymeleaf 页面 + 静态 JS/CSS
    ├── REST API /api/**
    ├── actuator /actuator/health（容器健康检查）
    └── H2 文件库（Docker 命名卷 order-data）
```

### 4.1 模块划分

```
com.example.order
├── OrderApplication
├── config/          SecurityConfig, OpenApiConfig, DataInitializer, DemoUserProperties, PageController
├── shared/          通用错误、分页、时间等
├── catalog/         商品与库存（未来可替换为库存服务）
├── user/            账号与认证
├── ordering/        订单领域
│   ├── api/         OrderPaymentPort（支付模块可见的唯一契约）
│   ├── domain/      CustomerOrder, OrderItem, OrderStatus
│   ├── repository/  OrderRepository
│   ├── service/     OrderService, OrderPaymentPortImpl
│   └── web/         OrderController
└── payment/         支付领域
    ├── domain/      Payment, PaymentStatus
    ├── repository/  PaymentRepository
    ├── service/     PaymentService, MockPaymentGateway
    └── web/         PaymentController
```

### 4.2 依赖规则

```
payment ──> ordering.api        （只能通过端口，不能碰订单表）
ordering ──> catalog.api        （只能通过端口，不能碰商品表）
各模块 web ──> 本模块 service ──> 本模块 repository
```

- 禁止跨模块直接注入 Repository、直接 Join 对方表。
- 订单只保存 `user_id`、`product_id`，不建立跨模块 JPA 关联（未来拆分时无需拆对象图）。
- 只有 `order_items → orders` 使用 JPA 级联，属于同一聚合。
- `open-in-view: false`：Controller 不直接返回实体；Service 在事务内完成明细加载与 DTO 映射，避免 `LazyInitializationException`。

### 4.3 跨模块端口

| 端口 | 定义位置 | 当前实现 | 调用方 | 拆分后 |
|---|---|---|---|---|
| `OrderPaymentPort` | `ordering/api` | 本地实现（条件更新订单） | payment | 替换为订单服务 HTTP 客户端 |
| `CatalogPort` | `catalog/api` | 本地实现（查询/扣减/回补库存） | ordering | 可替换为库存服务客户端 |

`OrderPaymentPort` 契约：

```java
PayableOrder loadForPayment(String orderNo, Long userId);
boolean markPaid(String orderNo, String paymentNo, LocalDateTime paidAt);
```

`PayableOrder` 仅含 `orderNo / userId / amount / status`，不暴露订单实体。`status` 使用 `ordering.api` 内的端口枚举（或字符串），payment 不 import `ordering.domain.OrderStatus`，保持模块边界。`loadForPayment` 只校验"订单存在且属于当前用户"并返回状态，不强制要求待支付；是否可支付、幂等返回还是拒绝，由支付服务判断。`loadForPayment` 用投影查询返回 DTO，不把 `CustomerOrder` 实体留在持久化上下文；`markPaid` 内部必须是条件更新（`status='PENDING_PAYMENT'`），返回是否更新成功，并且只 flush 不 clear，避免分离同一事务中的 `Payment` 实体。

## 5. 领域模型

### 5.1 关系

```
users 1 ─── N orders 1 ─── N order_items
orders 1 ─── 1 payments（按 order_no 唯一）
products 1 ─── N order_items（仅按 product_id 逻辑引用，金额与名称取快照）
```

### 5.2 表设计

> H2 中 `USER`、`ORDER` 是保留字，表名固定为 `users`、`orders`。另外 `ORDER` 也是 HQL 保留字，订单实体类命名为 `CustomerOrder`（表仍为 `orders`），避免 JPQL/HQL 出现 `unexpected token: Order`。

**users**

| 字段 | 类型 | 约束 |
|---|---|---|
| id | BIGINT | PK, 自增 |
| username | VARCHAR(50) | UNIQUE, NOT NULL |
| password_hash | VARCHAR(100) | NOT NULL，BCrypt |
| display_name | VARCHAR(50) | NOT NULL |
| created_at | TIMESTAMP | NOT NULL |

**products**

| 字段 | 类型 | 约束 |
|---|---|---|
| id | BIGINT | PK |
| name | VARCHAR(100) | NOT NULL |
| description | VARCHAR(500) | |
| price | DECIMAL(10,2) | NOT NULL, >= 0 |
| stock | INT | NOT NULL, >= 0 |
| enabled | BOOLEAN | NOT NULL，默认 true |
| created_at | TIMESTAMP | NOT NULL |

**orders**

| 字段 | 类型 | 约束 |
|---|---|---|
| id | BIGINT | PK |
| order_no | VARCHAR(32) | UNIQUE, NOT NULL |
| user_id | BIGINT | NOT NULL，逻辑引用 users.id |
| status | VARCHAR(20) | `PENDING_PAYMENT / PAID / CANCELLED` |
| total_amount | DECIMAL(12,2) | NOT NULL，服务端计算 |
| created_at | TIMESTAMP | NOT NULL |
| paid_at | TIMESTAMP | |
| cancelled_at | TIMESTAMP | |

索引：`(user_id, created_at DESC)`。

**order_items**

| 字段 | 类型 | 约束 |
|---|---|---|
| id | BIGINT | PK |
| order_id | BIGINT | FK → orders.id, NOT NULL |
| product_id | BIGINT | NOT NULL，逻辑引用 |
| product_name | VARCHAR(100) | 下单快照 |
| unit_price | DECIMAL(10,2) | 下单快照 |
| quantity | INT | 1–99 |
| subtotal | DECIMAL(12,2) | unit_price × quantity |

索引：`(order_id)`。

**payments**

| 字段 | 类型 | 约束 |
|---|---|---|
| id | BIGINT | PK |
| payment_no | VARCHAR(32) | UNIQUE, NOT NULL |
| order_no | VARCHAR(32) | **UNIQUE**, NOT NULL（一单一支付，天然幂等） |
| user_id | BIGINT | NOT NULL |
| amount | DECIMAL(12,2) | 支付金额快照 |
| channel | VARCHAR(20) | `MOCK` |
| status | VARCHAR(20) | `CREATED / SUCCEEDED / FAILED` |
| created_at | TIMESTAMP | NOT NULL |
| succeeded_at | TIMESTAMP | |

> 演示库仅建立 `order_items.order_id` 外键；跨模块引用靠 ID，迁移到生产库时在迁移脚本中补约束。

### 5.3 状态机

订单：

```
PENDING_PAYMENT ──支付成功──> PAID
PENDING_PAYMENT ──用户取消──> CANCELLED（回补库存）
其余流转一律拒绝（409）
```

支付单：

```
CREATED ──Mock 网关成功 + 订单 markPaid 成功──> SUCCEEDED
CREATED ──订单已取消 / 已支付（markPaid 返回 false）──> 事务回滚，不保留支付单，接口 409
FAILED 仅预留给真实网关失败 / 异步回调场景，Mock 同步流程不会产生；接入真实网关时需补充失败重试/复用支付单策略
```

## 6. 核心流程

### 6.1 创建订单

1. 从 Session 取当前用户；
2. 校验请求：1–20 个 SKU、数量 1–99、重复 `productId` 合并；
3. 按 `productId` 升序处理，避免并发多商品下单时交叉锁行导致死锁；
4. 逐个调用 `CatalogPort` 原子扣库存（见 7.1），任一失败即抛业务异常，整单回滚；
5. 以数据库中的价格计算总额，生成 `order_no`；
6. 保存订单 + 明细，状态 `PENDING_PAYMENT`，返回 201。

客户端传来的价格、总额一律不信任。

### 6.2 支付

1. `POST /api/payments { orderNo }`；
2. 先调 `OrderPaymentPort.loadForPayment`：订单不存在或不属于当前用户 → 404（不泄露他人订单是否存在）；
3. 按 `order_no` 查询已有支付单：若已存在 `SUCCEEDED` 支付单，直接返回该单（幂等），此时订单必然已支付；若存在非成功支付单（正常同步流程不会落库，属异常残留）→ 409 并记录日志，需人工清理；
4. 订单状态不是 `PENDING_PAYMENT` → 409；
5. 创建 `Payment(CREATED)`，金额取数据库中的订单总额快照（不信任请求），调用 `MockPaymentGateway`（同步成功）；
6. 调 `markPaid` 条件更新订单；若订单已被并发取消/支付导致更新 0 行，抛冲突异常，订单与支付单在同一事务回滚（不保留失败支付单）并返回 409；
7. 成功则支付单 `SUCCEEDED`、订单 `PAID`；当前同库同事务提交。

**顺序很重要**：归属校验必须发生在返回已有支付单之前，否则知道他人 `orderNo` 就能读到支付信息。

### 6.3 取消订单

1. 先按 `order_no + user_id` 查询：不存在或不属于当前用户 → 404（不泄露他人订单）；
2. 条件更新：`WHERE order_no=? AND user_id=? AND status='PENDING_PAYMENT'`；
3. 更新成功后才读取明细并回补库存；回补失败则整个取消事务回滚；
4. 更新 0 行（并发下已被支付/取消）→ 409；
5. 重复取消不会重复回补。

### 6.4 支付与取消的竞争

| 场景 | 结果 |
|---|---|
| 支付先成功，取消后到 | 取消条件更新失败 → 409 |
| 取消先成功，支付后到 | 加载到 `CANCELLED` → 直接 409；若两者并发，`markPaid` 更新 0 行 → 事务回滚，不保留支付单 → 409 |
| 顺序重复支付 | 先校验归属，再命中已有 SUCCEEDED 支付单直接返回，不产生第二笔 |
| 并发重复支付 | 唯一约束兜底，仅一笔成功；另一笔返回 409，重试时返回已有支付单 |

## 7. 关键设计

### 7.1 库存扣减与防超卖

使用条件更新，一条 SQL 完成"判断 + 扣减"：

```sql
UPDATE products
SET stock = stock - :qty
WHERE id = :id AND stock >= :qty AND enabled = true
```

- 影响行数为 0 → 该商品库存不足或已下架 → 抛异常，整个订单事务回滚；
- 依靠数据库行锁保证并发安全，单库部署下足够，不引入 Redis/分布式锁；
- JPA 批量更新使用 `flushAutomatically` / `clearAutomatically`，避免持久化上下文缓存旧库存；
- 但 `markPaid` 只加 `flushAutomatically`、不加 `clearAutomatically`：支付服务在同一事务里持有新建的 `Payment` 实体，clear 会把它 detached，后续置 `SUCCEEDED` 不会落库；
- H2 连接串设置 `LOCK_TIMEOUT=10000`，并发测试不会因默认锁等待过短而偶发失败；
- 行锁等待超时、更新冲突统一映射为 409，不把并发失败暴露成 500；
- 取消订单使用 `stock = stock + qty` 回补；
- 并发测试：10 个线程抢最后 1 件库存，只允许 1 单成功。

### 7.2 幂等

- 顺序重复支付：先校验订单归属，若已有 `SUCCEEDED` 支付单，直接返回，不重复扣款；
- 并发重复支付：`payments.order_no` 唯一约束兜底，最多一笔成功，另一笔返回 409，不产生第二笔；失败方重试时返回已成功的支付单；
- 支付/取消对订单状态的修改都是条件更新，重复执行不会产生副作用；
- 后端状态修改不做"先查再改"，避免检查与更新之间的竞态（归属与幂等查询除外，最终仍由条件更新/唯一约束兜底）；
- 下单接口不做服务端幂等（演示范围），由前端禁用按钮 + 提交中标记防重复提交；刷新页面后重新提交仍可能产生两笔待支付订单，属于已知限制。

### 7.3 一致性

**当前**：订单与支付同库，同一本地事务内完成"支付单落库 + 订单置为已支付"。

**拆分后**：不使用分布式事务。支付服务通过订单服务接口标记支付成功（带幂等键），失败重试；订单已取消则由支付侧转失败/退款；配合 MQ + outbox 与对账任务达到最终一致。

### 7.4 金额与快照

- 一律 `BigDecimal`，DB `DECIMAL`，禁止 `double`；
- 下单时快照商品名、单价，后续商品改价/改名不影响历史订单；
- 总额只由服务端计算。

### 7.5 单号规则

- 订单号：`ORD + yyyyMMddHHmmssSSS + 6 位随机数`；
- 支付单号：`PAY + yyyyMMddHHmmssSSS + 6 位随机数`；
- 唯一索引兜底；不在同一事务内重试（唯一约束冲突会把事务标记为 rollback-only），冲突概率极低，发生则返回 409 由用户重试。

## 8. API 设计

统一前缀 `/api`，Session 认证，JSON；分页响应统一为
`{ content, page, size, totalElements, totalPages }`。

| 方法 | 路径 | 说明 | 成功码 |
|---|---|---|---|
| GET | `/api/me` | 当前登录用户 | 200 |
| GET | `/api/products` | 上架商品列表（不翻页，演示目录小） | 200 |
| GET | `/api/products/{id}` | 商品详情 | 200 |
| POST | `/api/orders` | 创建订单 | 201 |
| GET | `/api/orders?status=&page=0&size=10` | 当前用户订单分页 | 200 |
| GET | `/api/orders/{orderNo}` | 订单详情（仅本人） | 200 |
| POST | `/api/orders/{orderNo}/cancel` | 取消待支付订单 | 200 |
| POST | `/api/payments` | 对订单发起支付 | 201 新建 / 200 幂等返回已有 |
| GET | `/api/payments/{paymentNo}` | 支付单详情（可选，调试用） | 200 |

分页参数：`page` 从 0 开始，`size` 默认 10、最大 50；`status` 只接受 `PENDING_PAYMENT / PAID / CANCELLED`。

创建订单请求：

```json
{
  "items": [
    { "productId": 1, "quantity": 2 },
    { "productId": 5, "quantity": 1 }
  ]
}
```

创建订单响应：

```json
{
  "orderNo": "ORD20261022153000123456789",
  "status": "PENDING_PAYMENT",
  "totalAmount": 857.00,
  "createdAt": "2026-10-22T15:30:00",
  "items": [
    {
      "productId": 1,
      "productName": "机械键盘 K87",
      "unitPrice": 299.00,
      "quantity": 2,
      "subtotal": 598.00
    },
    {
      "productId": 5,
      "productName": "降噪耳麦",
      "unitPrice": 259.00,
      "quantity": 1,
      "subtotal": 259.00
    }
  ]
}
```

支付请求 / 响应：

```json
{ "orderNo": "ORD20261022153000123456789" }
```

```json
{
  "paymentNo": "PAY20261022153100567890123",
  "orderNo": "ORD20261022153000123456789",
  "amount": 857.00,
  "channel": "MOCK",
  "status": "SUCCEEDED",
  "succeededAt": "2026-10-22T15:31:00"
}
```

错误响应：`{ "message": "...", "fieldErrors": { "items[0].quantity": "..." } }`

| 状态码 | 场景 |
|---|---|
| 400 | 参数校验失败 |
| 401 | 未登录（`/api/**` 返回 JSON，页面重定向登录） |
| 403 | 无权限 / CSRF 失败 |
| 404 | 商品或订单不存在；他人订单也返回 404，避免枚举 |
| 409 | 库存不足、订单状态不允许、支付冲突 |
| 500 | 未预期异常，仅返回通用提示，详细堆栈写日志 |

上表覆盖业务、校验、认证类错误；未知路径/容器级错误沿用 Spring Boot 默认错误响应，不作为对外接口契约。

## 9. 页面与交互

| 路由 | 页面 | 要点 |
|---|---|---|
| `/login` | 登录 | Spring Security 表单；错误提示 |
| `/` | 商品列表 | 卡片网格、库存/售罄状态、加购、购物车角标 |
| `/checkout` | 结算 | 重新拉取商品价格/库存；数量调整；提交错误提示 |
| `/orders` | 我的订单 | 分页、状态筛选、支付/取消入口 |
| `/orders/{orderNo}` | 订单详情 | 明细快照、金额、状态、操作按钮 |
| `/swagger-ui.html` | 接口文档 | UI 公开；接口调用需先登录，便于带 Session 调试 |

前端要点：

- 购物车只存 `productId + quantity`（localStorage），展示价格仅作参考；
- 结算页以服务端商品数据重新渲染，价格变化/下架/库存不足时明确提示；
- 所有用户数据用 `textContent` 渲染，避免 XSS；
- 无第三方 CDN，纯本地 CSS/JS，离线可用；
- UI 文案中文，状态使用颜色徽章区分。

## 10. 安全设计

- Spring Security 5.7：表单登录、Session、BCrypt 密码；
- 公开路径：`/login`、`/error`、`/favicon.ico`、`/css/**`、`/js/**`、`/swagger-ui.html`、`/swagger-ui/**`、`/webjars/**`、`/v3/api-docs/**`、`/actuator/health`；其余需登录；
- `/api/**` 未登录返回 401 JSON（自定义 `AuthenticationEntryPoint`），页面请求重定向登录；
- 登录成功后只允许站内相对路径跳转，防开放重定向；
- 退出登录使用 POST + CSRF，不使用 GET；
- Session Cookie `SameSite=Lax`；HTTPS 部署时再开启 `Secure`；
- 不启用 CORS（同源访问），不开启 H2 Console；
- 订单查询与操作均校验 `user_id`，越权统一 404；支付单查询同样校验归属，越权 404；
- 参数校验使用 Bean Validation，JPA 全部参数绑定，避免 SQL 注入。

**CSRF 取舍（待确认）**：页面登录/退出表单保留 CSRF；为便于 Swagger UI 调试，`/api/**` 不启用 CSRF，依赖 `SameSite=Lax` + 无 CORS + JSON 请求的同源限制。这仅适用于本机/内网演示；若公网部署，需改为 CSRF Token 或 JWT + 自定义请求头方案。

演示账号通过配置提供（默认 `demo / 123456`），部署时可用环境变量覆盖。

## 11. 数据初始化

启动时 `DataInitializer` 幂等写入：

- 用户：1 个演示账号（配置可覆盖）；
- 商品：8 条，其中一个商品库存为 1，用于演示/验证超卖。

示例商品：机械键盘、无线鼠标、27 英寸显示器、USB-C 扩展坞、降噪耳麦、人体工学椅、桌面氛围灯、限量鼠标垫。

实现方式：`ApplicationRunner` 中判断 `users` / `products` 是否为空，不覆盖已有数据；由 `app.seed.enabled` 控制（默认 true，测试 profile 关闭）。演示阶段 JPA `ddl-auto=update` 建表；生产环境应改为 Flyway 迁移。演示账号只在 `users` 为空时创建，修改配置后不会自动改已存在账号的密码；需要重置数据卷或手动改库。

## 12. 配置与运行

`application.yml` 关键项：

```yaml
server:
  port: 8080
  servlet:
    session:
      cookie:
        same-site: lax
spring:
  datasource:
    url: jdbc:h2:file:./data/orderdb;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  jackson:
    time-zone: Asia/Shanghai
    serialization:
      write-dates-as-timestamps: false
management:
  endpoints:
    web:
      exposure:
        include: health
app:
  seed:
    enabled: true
```

本地开发（Windows）：

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=8090
# http://localhost:8090
```

本机 8080 已被现有 `sub2api` 容器占用：容器部署映射到宿主机 8081，Windows 本地开发用 8090，互不冲突。首次运行会在项目下生成 `data/orderdb.mv.db`。

## 13. 构建与部署

### 13.1 方式

- **开发**：Windows + IDEA / `mvnw.cmd`，JDK 8，本地端口 8090；
- **构建与部署**：WSL2 Ubuntu 中 Docker 多阶段构建 + Compose 一键部署，Windows 不再打包；
- Docker 29.6.2 + Buildx 0.35 + Compose v5.3.1，Maven/JDK 全部在构建镜像内；
- 宿主机端口 `8081`（8080 已被现有 sub2api 容器占用），容器内仍为 8080；
- Docker Hub 直连超时，daemon 已配置镜像加速器 `https://docker.1ms.run`，镜像拉取正常；
- Compose v5 是 CLI 插件版本号，compose 文件不写废弃的 `version:` 字段；
- Jenkins 等 CI 留到服务稳定后的独立阶段，不纳入本期。

### 13.2 产物

```
Dockerfile                 # 多阶段：Maven 3.9 + JDK 8 构建，JRE 8 运行
.dockerignore              # target/, data/, backups/, .git/, docs/
docker-compose.yml         # build、端口 8081:8080、数据卷、健康检查
scripts/deploy.cmd         # Windows 双击一键部署
scripts/deploy.sh          # WSL 内一键部署
src/main/resources/application-docker.yml
```

Dockerfile：

```dockerfile
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM eclipse-temurin:8-jre-jammy
WORKDIR /app
COPY --from=build /build/target/order-system.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- `go-offline` 只用于预热依赖缓存，失败不阻断构建（`|| true`）；真正依赖在 `mvn package` 阶段解析，缺少时会明确报错；
- `mvn package` 默认执行全部测试；测试失败则镜像构建失败，旧容器继续运行；
- Maven 依赖缓存在 BuildKit 的 `/root/.m2` cache mount 中，第二次构建明显加快；
- 运行时镜像自带 `curl`，健康检查无需额外安装；
- 基础镜像已在本机验证：`maven:3.9-eclipse-temurin-8`（Maven 3.9.16 / JDK 1.8）、`eclipse-temurin:8-jre-jammy`。

Compose 要点：

- `build: .`，`ports: ["8081:8080"]`；
- 命名卷 `order-data:/app/data` 持久化 H2；
- `SPRING_PROFILES_ACTIVE=docker`；
- `TZ=Asia/Shanghai`、`JAVA_TOOL_OPTIONS=-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai`；
- `restart: unless-stopped`；
- healthcheck：`curl -fsS http://localhost:8080/actuator/health`，`interval: 10s`、`timeout: 3s`、`retries: 10`、`start_period: 30s`（curl 已内置）；
- `mem_limit: 1g`：JVM 堆 `-Xmx512m`，其余留给 Metaspace、线程栈、直接内存；768m 过于紧张，容易在构建/启动阶段被 OOM Kill。

`application-docker.yml` 仅覆盖数据源为 `jdbc:h2:file:/app/data/orderdb;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000`。

### 13.3 一键部署

Windows 双击 `scripts\deploy.cmd`（或命令行执行），脚本进入 WSL 并运行：

```bash
set -euo pipefail
cd /mnt/e/zhaoo_workspace/idea_workspace/demo
docker info >/dev/null 2>&1 || { echo "Docker 未运行，请先启动 WSL/Docker"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "缺少 docker compose 插件"; exit 1; }
if command -v ss >/dev/null 2>&1; then
  running_service=$(docker compose ps --status running --services 2>/dev/null | grep -x 'order-app' || true)
  if [ -z "$running_service" ] && ss -ltn | grep -q ':8081 '; then
    echo "宿主机 8081 已被其他进程占用"
    exit 1
  fi
fi
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
```

- 脚本先做 Docker daemon、Compose 插件、8081 端口三项预检，失败时给出明确提示；
- 端口预检会跳过"本项目容器已在运行"的情况，保证重复部署不会被自己的 8081 映射误拦；
- WSL 发行版名默认 `Ubuntu`，项目路径在 `deploy.cmd` 顶部集中定义；移动项目目录时需同步修改；
- 构建、测试、打包、启动一条命令完成；
- `--wait` 等待容器 healthy 后才返回，`--wait-timeout 300` 避免首次构建/启动被误判超时；
- 首次构建需要拉镜像、下载 Maven 依赖，耗时较长；后续复用镜像与依赖缓存；
- 测试失败时构建中止，不会替换正在运行的旧容器；
- 浏览器访问 `http://localhost:8081`。

排查：

```bash
docker compose logs -f order-app
docker compose build --no-cache     # 仅在缓存异常时使用
```

### 13.4 数据与运维

- Maven 构建在容器内执行，源码由 `/mnt/e` 复制进构建上下文；不要在 WSL 里直接对 `/mnt/e` 跑 `mvnw`；
- 开发数据（Windows 项目下 `./data`）与容器卷数据相互独立；
- 备份：先停应用再复制 H2 文件，避免复制到写入中的数据库；`docker compose down -v` 会删除数据卷，慎用：

```bash
docker compose stop order-app
VOL=$(docker volume ls --format '{{.Name}}' | grep 'order-data' | head -n1 || true)
[ -n "$VOL" ] || { echo "未找到 order-data 卷"; exit 1; }
mkdir -p backups
docker run --rm -v "$VOL":/data -v "$PWD/backups":/backup \
  eclipse-temurin:8-jre-jammy sh -c 'tar czf /backup/order-data-$(date +%F-%H%M).tgz -C /data .'
docker compose start order-app
```

恢复时先停容器，清空卷内容后解包，再启动；清空前确认备份文件存在。`docker compose down`（不带 `-v`）不会删除数据卷。备份包含账号哈希与订单数据，不要提交到 Git 或放进构建上下文；`backups/` 已加入 `.gitignore` / `.dockerignore`。

- H2 只支持单实例，`order-app` 固定 1 副本；
- WSL 不会随 Windows 登录自动启动，需要时用计划任务触发 `wsl -d Ubuntu ... docker compose up -d`；
- 仅本机/局域网演示。Compose 默认把 8081 发布到所有网卡，弱密码账号不要暴露到不可信网络；公网访问需额外做 HTTPS、强密码、Secure Cookie 和安全加固。

### 13.5 后续 CI（不在本期）

服务稳定后再加 Jenkins（或 GitHub/Gitea Actions）：Jenkins 容器建议映射 9090，流水线直接复用本 Dockerfile 与 compose，只负责触发、构建历史与自动部署。Jenkins 需要 Git 仓库、插件和 Docker socket 权限，本期不引入。

## 14. 测试方案

### 14.1 自动化测试（H2 内存库，不 Mock Repository）

| 编号 | 用例 | 断言 |
|---|---|---|
| T1 | 正常下单 | 库存扣减、总额/单价快照正确、状态待支付 |
| T2 | 库存不足（多商品部分可扣） | 409，整单回滚：先前商品库存不变 |
| T3 | 取消订单 | 状态取消、库存回补 |
| T4 | 重复取消 | 409，库存只回补一次 |
| T5 | 支付成功 | 支付单 SUCCEEDED、订单 PAID |
| T6 | 重复支付（顺序 + 并发） | 顺序重复返回同一支付单；并发重复仅一笔成功，另一笔 409 |
| T7 | 已取消订单支付 / 已支付订单取消 | 409 |
| T8 | 越权访问订单/支付单 | 404 |
| T9 | 并发抢购 | 10 线程抢 1 件库存，恰好 1 单成功，库存 0 |
| T10 | 未登录访问 API | 401 JSON |

**测试配置与命名**

`src/test/resources/application-test.yml`，测试类统一 `@ActiveProfiles("test")`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:orderdb-${random.uuid};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000
    username: sa
    password: ""
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
app:
  seed:
    enabled: false
```

- `${random.uuid}` 让每个 Spring 测试上下文使用独立内存库，避免 `create-drop` 清掉其他上下文的表；
- 测试 profile 关闭 `DataInitializer` 种子，避免测试之间依赖或破坏种子数据；需要用户/商品的测试自行创建；
- 测试类命名必须是 `*Test`（如 `OrderServiceTest`）。Surefire 默认不扫描 `*IT`，命名成 `OrderServiceIT` 会导致 `mvn test` 显示成功但实际一个测试都没跑；
- 每个测试自行准备并清理数据，避免共享内存库相互污染；
- 并发测试方法不能加 `@Transactional`：测试事务中的数据对其他线程不可见，会退化成串行或直接失败；种子数据要先提交，测试结束后清理；
- 断言"成功数=1、库存=0、订单数=1"这类最终状态，不断言线程执行顺序。

### 14.2 端到端验收

1. 登录失败/成功、退出；
2. 加购多件商品、修改数量、结算；
3. 下单后库存减少、订单出现在列表；
4. 支付后状态变已支付，重复点击无副作用；
5. 另下一单并取消，库存回补；
6. 并发脚本或测试验证不超卖；
7. Swagger 中读取接口、带 Session 调用；
8. Docker 重启后数据仍在。

## 15. 未来拆分路径

1. **当前**：模块化单体，单应用单库，端口隔离；
2. **拆应用**：将 `payment` 提为独立 Spring Boot 应用与独立数据库；`OrderPaymentPort` 的本地实现替换为 HTTP/Feign 客户端；
3. **治理**：网关、JWT/SSO、超时/重试/幂等键、限流、集中日志与链路追踪；
4. **最终一致**：订单/支付事件经 outbox + MQ 传递，增加对账与补偿任务；
5. **数据层**：H2 换成 PostgreSQL/MySQL，订单库与支付库分离。

拆分前不建议引入这些组件，避免为假想需求增加复杂度。

## 16. 限制与风险

| 风险 / 限制 | 影响 | 应对 |
|---|---|---|
| H2 文件库单实例 | 不能多副本、无高可用 | 演示接受；扩容前迁移 PostgreSQL |
| `ddl-auto=update` | 不适合生产迁移 | 后续引入 Flyway |
| Mock 支付总是成功 | 无法演示支付失败 | 按 Q3 不做失败模拟；`FAILED` 仅预留给真实网关 |
| `/api/**` 关闭 CSRF | 公网场景存在 CSRF 风险 | 仅内网演示；公网改为 Token/JWT |
| Maven Wrapper 首次下载 | 网络受限时 Windows 本地构建失败 | 用已拉取的 Maven 镜像生成 wrapper；仍失败则用 IDEA 内置 Maven 或 Docker 构建，部署不依赖 wrapper |
| 购物车存 localStorage | 换浏览器/清缓存丢失 | 演示可接受；不在本期做服务端购物车 |
| WSL 自动启动 | Windows 重启后容器未运行 | 手动 `docker compose up -d` 或计划任务 |
| 8080 已被 sub2api 占用 | 端口冲突 | 容器固定映射 `8081:8080`；Windows 本地开发用 8090 |
| 8081 默认对所有网卡开放 | 弱密码可能被局域网访问 | 仅可信网络；可改为 `127.0.0.1:8081:8080`；公网前改密并加 HTTPS |
| Docker Hub 直连超时 | 拉取构建镜像失败 | 使用 daemon 已配置的 `docker.1ms.run` 加速器；已验证 JDK8/Maven 镜像 |
| 首次容器构建慢 | 部署等待时间长 | 拉取基础镜像 + Maven 依赖缓存；后续增量构建复用 BuildKit cache |
| `/mnt/e` 构建上下文 | 源码复制较慢 | 仅复制 `pom.xml` 与 `src`，并用 `.dockerignore` 排除 target/data/docs |
| 测试类命名不匹配 Surefire | 构建"成功"但测试未执行 | 统一 `*Test`；DoD 检查 Surefire 报告的 tests 数 |
| H2 并发锁等待 | 并发测试偶发超时 | 连接串加 `LOCK_TIMEOUT=10000`；Hikari 连接池调到 20 |
| 重复下单 | 刷新/重复提交可能生成两笔待支付订单 | 前端禁用按钮防误触；演示范围不做服务端幂等，文档明示 |
| Docker daemon / Compose 未就绪 | 一键部署直接报错难定位 | 部署脚本预检 `docker info`、`docker compose version`、8081 端口 |
| `dependency:go-offline` 不完整 | 构建阶段仍需联网补依赖 | 构建机需可访问 Maven Central；缓存缺失时 `package` 自动补下载 |
| 会话存内存 | 容器重启后需重新登录 | 演示接受；多实例前引入 Spring Session |
| 新镜像启动失败 | 构建成功后旧容器已被替换，服务可能中断 | 数据卷保留；看日志修复后重跑部署，不做自动回滚 |

## 17. 待评审问题（不阻塞，已有默认值）

| # | 问题 | 默认方案 |
|---|---|---|
| Q1 | `/api/**` 是否为 Swagger 调试关闭 CSRF？ | 关闭，公网部署再加固 |
| Q2 | 演示账号固定还是配置化？ | 配置化，默认 `demo / 123456` |
| Q3 | 是否需要一个"支付失败"模拟开关？ | 不做，保持 Mock 必成功 |
| Q4 | 只本机演示，还是需要局域网/公网访问？ | 本机 + WSL Docker；公网另加 TLS |
| Q5 | 订单是否需要"已完成"状态？ | 不做，只保留待支付/已支付/已取消 |
| Q6 | 商品列表是否需要分页/搜索？ | 不做，8 条种子数据全量返回 |

评审确认后按 [实施计划](plan.md) 执行。

## 18. 评审修订记录（v0.3 → v0.4）

| 轮次 | 重点 | 主要修订 |
|---|---|---|
| 第 1 轮 | 一致性与环境 | Swagger 路径纳入公开路径；订单实体改 `CustomerOrder` 规避 HQL 保留字；订单/支付单越权统一 404；退出改 POST + CSRF；T8 覆盖支付单 |
| 第 2 轮 | 事务、幂等、并发、构建资源 | 支付冲突回滚、不保留 FAILED；归属校验先于幂等返回；`markPaid` 只 flush 不 clear；补充顺序/并发支付语义；单号规则；测试 profile 与 `*Test` 命名；`mem_limit` 1g；H2 锁等待 |
| 第 3 轮 | 计划可执行性 | M0 测试配置与种子开关；M1/M2 种子顺序；M4 最小订单页；M5 Mock 网关；M8 预检/备份/`go-offline` 兜底；测试隔离；重复下单限制；WSL 脚本 LF |

## 19. 实现记录（v1.0，2026-10-22）

设计与实现整体一致，M0–M8 已完成并通过验收。本节记录实际执行结果与实现过程中对细节的澄清，便于后续评审与交接。

### 19.1 实际验证结果

- `.\mvnw.cmd clean test`：15 个测试全部通过
  - `OrderServiceTest`：T1、T2、T3、T4、T8（订单越权）、T9，共 6 个
  - `PaymentServiceTest`：T5、T6（顺序 + 并发）、T7（两种非法流转）、T8（支付越权），共 6 个
  - `SecurityTest`：未登录 API 401 JSON、未登录页面重定向、登录后受保护接口可访问，共 3 个
- 支付 API 手工验证：首次支付 201、重复支付 200 返回同一支付单、已取消订单支付 409、已支付订单取消 409
- 无头 Chrome 端到端验证 27 项全部通过：错误/正确登录、商品渲染、购物车角标、结算金额、下单、支付、订单筛选、分页、取消回补库存、退出清空购物车、未登录重定向、移动端无横向溢出、无控制台报错
- Docker 一键部署：`scripts/deploy.cmd` 在 Windows 双击成功，镜像构建日志显示 `Tests run: 15, Failures: 0, Errors: 0`、`BUILD SUCCESS`；容器 `demo-order-app-1` healthy，Windows 侧 `http://localhost:8081` 可访问
- 部署后浏览器端到端验证 21 项全部通过；容器内存限制 `1073741824`（1 GiB）、`SPRING_PROFILES_ACTIVE=docker`、`JAVA_TOOL_OPTIONS=-Xms256m -Xmx512m` 实际生效
- 持久化与运维验证：`docker compose restart order-app` 后订单仍为 PAID；重新执行部署脚本后数据保留；备份流程（停容器 → 打包 `demo_order-data` 卷 → 启动）产出 `backups/order-data-2026-10-22-100221.tgz`，容器恢复 healthy 且订单可查
- 本地最终 `.\mvnw.cmd clean package`：BUILD SUCCESS，15 个测试全部通过

### 19.2 实现澄清

| 项目 | 设计表述 | 实际实现 |
|---|---|---|
| 并发重复支付 | 一笔成功，另一笔 409 | 唯一约束保证只有一笔支付单；竞争失败方若在赢家提交后才检查，会直接返回已成功的同一支付单（200），否则由唯一约束转为 409。两种结果均保持"只有一笔支付单"，测试接受两种分支 |
| 支付单插入冲突 | 事务回滚并返回 409 | `PaymentService` 捕获 `DataIntegrityViolationException` 并转为业务冲突，异常抛出后订单与支付单同一事务回滚 |
| 测试数据源 | `DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000` | 额外增加 `DB_CLOSE_ON_EXIT=FALSE`，消除测试 JVM 退出时的 H2 关闭告警 |
| 登录成功跳转 | 仅允许站内相对路径 | 固定跳转 `/`，从源头避免开放重定向 |
| 图标 | 无 | 增加本地 `static/favicon.ico`，避免浏览器控制台 404 |
| 接口文档 | Swagger UI | 增加 `OpenApiConfig`（标题/版本/说明）、控制器分组与摘要，并隐藏 `@AuthenticationPrincipal` 参数 |
| Thymeleaf 缓存 | 未提及 | 保持 Spring Boot 默认（打包运行默认开启缓存） |

### 19.3 模块边界检查

- `payment` 仅依赖 `ordering.api.OrderPaymentPort`、`PayableOrder`、`PayableOrderStatus`，未引用订单实体或 Repository；
- `ordering` 仅依赖 `catalog.api.CatalogPort` 与 `ProductInfo`，未引用商品 Repository；
- 跨模块仅通过 ID 关联，唯一 JPA 级联为 `order_items → orders`。

