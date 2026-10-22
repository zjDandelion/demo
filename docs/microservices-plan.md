# 订单/支付微服务拆分 · 实施计划

| 项目 | 内容 |
|---|---|
| 版本 | v1.0（2026-10-22，已完成） |
| 状态 | M0–M6 全部实施完成，验收报告已输出；等待用户最终确认 |
| 配套文档 | [微服务拆分设计](microservices-design.md)、[单体设计方案](design.md) |
| 执行方式 | M0–M6 顺序推进；M1–M4 使用本地端口，M5 切换 8081，M6 总验收 |
| 命名前缀 | `na-` |

## 1. 目标与交付物

在现有 `E:\zhaoo_workspace\idea_workspace\demo` 内完成订单/支付微服务拆分，交付：

1. Maven 多模块工程：`na-order-platform`、`na-payment-contract`、`na-order-service`、`na-payment-service`；
2. 两个可独立构建、独立启动的服务，服务间通过内部 HTTP API 通信；
3. PostgreSQL 16：一个实例、`na_orderdb` / `na_paymentdb` 两个 database、两个独立账号；
4. 同步 Saga：支付尝试日志、条件更新、补偿退款/关闭、定时对账；
5. 公开 API 与页面保持不变，前端无需修改；
6. Docker Compose 一键部署、健康检查、`.env` 密钥、备份脚本；
7. 自动化测试、真实 PostgreSQL E2E、故障注入、并发与重启验证；
8. 更新 README、设计与计划文档，记录实际实现与偏差。

## 2. 已确认前置条件

| 项 | 结果 |
|---|---|
| 服务数量 | 2 个：`na-order-service`、`na-payment-service` |
| 数据库 | PostgreSQL 16，一个实例，两个 database / 两个账号 |
| 一致性 | 同步 Saga + 补偿 + 对账 |
| 旧数据 | 删除，不迁移 |
| 入口 | 仅订单服务暴露 8081；支付服务仅内网 |
| 不引入 | MQ、注册中心、网关、Feign、分布式事务 |
| 部署 | WSL2 Ubuntu + Docker Compose；Windows 双击 `scripts\deploy.cmd` |

## 3. 执行原则

1. **当前目录不是 Git 仓库**：M0 先做源码快照，保留旧镜像和旧数据卷；不依赖 Git 分支回滚。
2. **M1–M4 不切换 8081**：旧单体容器继续在 8081 提供演示；新服务用 8090/8091 本地端口和 5433 PostgreSQL 调试。M5 才切换。
3. **旧源码先复制再删除**：M2/M3 从旧 `src/` 复制代码到新模块，M4 验收后删除旧 `src/`，期间旧源码不参与构建。
4. **每个里程碑必须可构建、可测试**；未通过当前里程碑，不进入下一个。
5. **不在 `@Transactional` 中调用远程服务**；支付编排和渠道调用都在事务外。
6. **不修改公开 API 路径和响应字段**；前端保持不动。
7. **所有跨服务状态更新必须幂等**，对账任务可以安全重复执行。
8. **旧 H2 卷只读保留**，新栈使用新 PostgreSQL 卷；验收前不删除旧镜像/旧卷。

## 4. 里程碑

### M0 基线冻结与准备

- [x] 生成源码快照：`backups/source-v1-<timestamp>.tar.gz`，排除 `target/`、`data/`、`backups/`、`logs/`；
- [x] 保留旧镜像 `order-system:local` 和卷 `demo_order-data`；
- [x] 确认旧容器 `demo-order-app-1` 健康、8081 可访问、演示订单可查；
- [x] 记录基线测试：15 个测试全部通过；
- [x] 确认 `.env` 变量清单与命名（见 M1）；
- [x] 拉取 `postgres:16` 镜像，确认 WSL Docker 可用；
- [x] 确认 JDK 8 / Maven Wrapper 可用，记录 Flyway、PostgreSQL JDBC 版本兼容性验证项。

**验收**

- [x] 旧系统仍可运行，快照可解压；
- [x] 旧镜像、旧卷、旧容器均保留；
- [x] 15 个基线测试结果已记录。

### M1 多模块骨架、契约与 PostgreSQL

- [x] 根 `pom.xml` 改为父 POM `na-order-platform`，`packaging=pom`，groupId `com.example.na`，version `2.0.0`；
- [x] 新建模块 `na-payment-contract`、`na-order-service`、`na-payment-service`；
- [x] 父 POM 固定 Java 8、Boot 2.7.18、Flyway 9.22.x、PostgreSQL JDBC 42.7.x（Java 8 兼容，用于支持 PG 16）；
- [x] `na-payment-contract` 增加：
  - [x] `PaymentCommand`
  - [x] `PaymentView`
  - [x] `PaymentStatusView`
  - [x] `RefundCommand`
  - [x] `ClosePaymentCommand`
  - [x] `InternalHeaders`（`X-Service-Token`、`X-Request-Id`）
  - [x] `PaymentErrorCode`
- [x] 两个服务增加最小启动类、`/actuator/health`、PostgreSQL 数据源、Flyway；
- [x] `na-order-service` 增加 Flyway：`V1__init_order_schema.sql`（users、products、orders、order_items、order_payment_attempt）；
- [x] `na-payment-service` 增加 Flyway：`V1__init_payment_schema.sql`（payments）；
- [x] 新增 `scripts/postgres/init-databases.sh`，首次启动创建两个 database / 两个账号，撤销 PUBLIC CONNECT；
- [x] 新增 `docker-compose.yml`：`na-postgres` 服务、健康检查、`127.0.0.1:5433`、`na-pg-data` 卷；
- [x] 新增 `.env.example`，`deploy.sh` 首次运行时生成 `.env`；
- [x] 两个模块的 Dockerfile 骨架（多阶段构建、BuildKit Maven 缓存）；
- [x] `.dockerignore` 更新：排除旧 `src/`、各模块 `target/`、`data/`、`backups/`、`logs/`、`.git/`、`docs/`；
- [x] 验证：`.\mvnw.cmd clean package` 构建父工程与三个模块；
- [x] 验证：本地启动两个空服务，Flyway 在 `na_orderdb` / `na_paymentdb` 建表，health 返回 UP；
- [x] 验证：`na_order_user` 无法访问 `na_paymentdb`，`na_payment_user` 无法访问 `na_orderdb`。

**验收**

- [x] 多模块构建通过；
- [x] 两个服务可独立启动；
- [x] 两个 database 由 Flyway 初始化；
- [x] 两套账号权限隔离；
- [x] 无跨模块 Java 依赖。

### M2 抽取 `na-payment-service`

- [x] 从旧 `src` 复制支付领域代码到 `com.example.na.payment`：
  - [x] `Payment`、`PaymentStatus`
  - [x] `PaymentRepository`
  - [x] `PaymentService`
  - [x] `PaymentNoGenerator`
  - [x] `MockPaymentChannel`（由 `MockPaymentGateway` 改造）
- [x] 删除对 `OrderPaymentPort`、订单实体、用户实体、商品实体的一切引用；
- [x] 支付服务增加 `PaymentChannel` 接口（`charge` / `refund` / `close`，Mock 同步实现）；
- [x] 支付逻辑改造：
  - [x] 事务 1：创建/加载支付单 `CREATED` 并提交；
  - [x] 事务外：调用渠道，`paymentNo` 作为幂等键；
  - [x] 事务 2：`CREATED → SUCCEEDED`；
  - [x] 已 `SUCCEEDED` 重复请求返回 200 同一支付单；
  - [x] 已 `CREATED` 按 `paymentNo` 恢复渠道调用；
  - [x] 金额/用户/币种与已有支付单不一致 → 409；
  - [x] 退款：`SUCCEEDED → REFUNDED`，幂等；
  - [x] 关闭：`CREATED → FAILED`，幂等；
- [x] 新增 `InternalPaymentController`：
  - [x] `POST /internal/v1/payments`
  - [x] `GET /internal/v1/payments/{paymentNo}`
  - [x] `GET /internal/v1/payments/by-order/{orderNo}`
  - [x] `POST /internal/v1/payments/{paymentNo}/refund`
  - [x] `POST /internal/v1/payments/{paymentNo}/close`
- [x] 新增内部安全：
  - [x] `InternalTokenFilter` 校验 `X-Service-Token`，常量时间比较；
  - [x] 无表单登录、无 Session、无 CSRF；
  - [x] 仅 `/actuator/health` 和内部 API 可达；
- [x] 新增统一错误响应 `{code, message}` 与异常处理；
- [x] 支付服务关闭公网 Swagger（docker profile）；
- [x] 适配 `PaymentServiceTest`：不再依赖订单；新增 token、退款、关闭、金额冲突用例；
- [x] 本地启动支付服务 8091，用 curl 验证：无 token 401、有 token 支付 201、重复支付 200、退款 200、关闭 200；
- [x] 验证支付服务代码中不存在 `Order`、`User`、`Product` 实体/Repository 引用。

**验收**

- [x] 支付服务可独立启动；
- [x] 内部 API 契约与设计一致；
- [x] 支付/退款/关闭幂等；
- [x] 支付服务测试全部通过；
- [x] 支付服务不依赖订单模块。

### M3 改造 `na-order-service`

- [x] 从旧 `src` 复制 `catalog`、`ordering`、`user`、`config`、`shared`、页面与静态资源到 `com.example.na.order`；
- [x] 删除 order-service 中的 `payment` 领域、Repository、Controller、支付测试；
- [x] 删除 `ordering/api/OrderPaymentPort`、`PayableOrder`、`PayableOrderStatus`、`OrderPaymentPortImpl`；
- [x] 新增 `OrderPaymentAttempt` 实体、`PaymentAttemptStatus`、Repository：
  - [x] `order_no` 唯一
  - [x] `STARTED / SUCCEEDED / FAILED / REFUND_PENDING / REFUNDED`
  - [x] 条件状态更新、`attempt_count`、`last_error`、`updated_at`
- [x] 新增 `PaymentClient` + `PaymentClientProperties` + `PaymentClientConfig`：
  - [x] RestTemplate 连接超时 2s、读取超时 5s；
  - [x] 注入 `APP_PAYMENT_BASE_URL`、`APP_PAYMENT_SERVICE_TOKEN`；
  - [x] 透传 `X-Request-Id`；
  - [x] 映射内部错误到订单侧异常；
- [x] 新增 `OrderPaymentOrchestrator`：
  - [x] 校验订单归属、状态、金额；
  - [x] 写 attempt `STARTED`；
  - [x] 调支付服务（事务外）；
  - [x] 校验响应 `orderNo / amount / status`；
  - [x] 同一 orderdb 事务内 markPaid + attempt `SUCCEEDED`；
  - [x] markPaid 0 行时按订单状态补偿：CANCELLED → 退款，PAID → 幂等返回；
  - [x] 金额不一致 → 退款并拒绝；
  - [x] 支付服务不可用 → attempt 保持 `STARTED`，返回 503；
- [x] 新增 `PaymentBffController`，保持：
  - [x] `POST /api/payments`
  - [x] `GET /api/payments/{paymentNo}`
  - [x] 首次 201、重复 200、冲突 409、不可用 503；
- [x] 新增 `OrderPaymentReconciliationJob`：
  - [x] `@Scheduled(fixedDelay=60000)`；
  - [x] 扫描 `STARTED` / `REFUND_PENDING`；
  - [x] 按设计第 10.3 节决策表处理；
  - [x] `app.reconciliation.enabled` 开关，test profile 关闭；
- [x] 订单取消仍只走本地事务：条件更新 + 库存回补，不调用支付服务；
- [x] 适配 `OrderServiceTest`、`SecurityTest`；
- [x] 新增编排测试：成功、支付不可用、取消竞争退款、金额不一致、重复支付、对账补 PAID/退款；
- [x] 本地启动订单服务 8090（连接 8091 支付服务），验证完整下单支付流程；
- [x] 验证公开 API 响应字段与单体版本一致，前端无需改动。

**验收**

- [x] 订单服务独立启动；
- [x] 公开 API 行为保持兼容；
- [x] Saga 日志、编排、补偿、对账代码可运行；
- [x] 订单服务不包含支付实体/Repository；
- [x] 编排与对账测试通过。

### M4 本地双服务联调与故障验证

- [x] 启动 PostgreSQL（`docker compose up -d na-postgres`）；
- [x] 启动支付服务 8091、订单服务 8090；
- [x] 执行原有浏览器 E2E：登录、商品、购物车、下单、支付、订单列表/详情、取消、分页、筛选、退出；
- [x] 验证重复支付：两个并发请求只产生一笔支付单，订单只 PAID 一次；
- [x] 验证取消与支付竞争：最终状态为 CANCELLED + REFUNDED，库存已回补；
- [x] 验证支付服务停机：
  - [x] 支付返回 503；
  - [x] 订单保持 PENDING；
  - [x] 恢复支付服务后重试成功；
- [x] 验证读超时/结果未知：
  - [x] 使用测试专用 `app.mock.delay-ms` 让渠道超过读取超时；
  - [x] attempt 保持 `STARTED`；
  - [x] 对账任务恢复后补 PAID 或退款；
- [x] 验证崩溃恢复：
  - [x] 模拟支付成功但未 markPaid：手工准备 attempt `STARTED` + payment `SUCCEEDED`，对账补 PAID；
  - [x] 模拟订单 CANCELLED + payment `SUCCEEDED`，对账补退款；
- [x] 验证金额不一致：构造错误金额响应，确认不 markPaid、触发退款/失败；
- [x] 验证两个服务重启后数据保留；
- [x] 验证 order 账号无法直接访问 paymentdb；
- [x] 删除旧 `src/`（旧源码快照仍在），确认新模块构建不依赖旧代码；
- [x] 记录联调结果与偏差。

**验收**

- [x] 原有 21 项浏览器 E2E 全部通过（实际 23 项检查全部通过）；
- [x] 新增 Saga/故障场景全部通过；
- [x] 真实 PostgreSQL 下条件更新、唯一约束、Flyway 行为验证通过；
- [x] 无跨服务数据库访问；
- [x] 旧源码已删除，新工程自包含。

**M4 联调记录（2026-10-22）**

| 场景 | 结果 |
| --- | --- |
| 浏览器 E2E（`tests/e2e/e2e.js`） | 23 项检查通过、0 失败，浏览器控制台无错误 |
| 并发重复支付 | 同时两请求返回 201 + 200，同一 `paymentNo`，仅一笔支付、订单一次 PAID |
| 取消与支付竞争（渠道延迟 3s） | 取消 200 → 支付 409「订单已取消，支付已退款」，订单 CANCELLED、attempt/payment REFUNDED、库存回补 |
| 支付服务停机 | 支付 503，订单 PENDING，attempt STARTED 记录错误；恢复后重试 200 并 PAID |
| 读超时/结果未知（渠道延迟 7s） | 5.1s 返回 503，订单 PENDING、attempt STARTED；渠道 7s 后 SUCCEEDED，对账 20s 内补 PAID |
| 崩溃恢复 S5（SQL 构造 STARTED + SUCCEEDED） | 对账补 PAID，attempt SUCCEEDED |
| 崩溃恢复 S6（SQL 构造 CANCELLED + REFUND_PENDING + SUCCEEDED） | 对账退款，attempt/payment REFUNDED |
| 金额不一致（内部 API 构造错误金额支付单） | 不 markPaid；补偿退款触发，退款读超时后 attempt 保持 REFUND_PENDING，对账最终置 REFUNDED |
| 双服务重启 | 重新登录正常，历史订单（20 笔）状态完整保留 |
| 跨库隔离 | `na_order_user` 连接 `na_paymentdb` 被拒绝 |
| 旧源码删除 | 快照 `backups/source-v1-20261022-112630.tar.gz` 已确认包含旧 `src/`；删除后 `mvn compile` 通过 |

**偏差**：① 补偿退款可能超过 5s 读取超时，此时接口返回 503 且 attempt 暂留 `REFUND_PENDING`，由对账收敛，属于设计预期；② 本地开发为便于测试，支付服务监听 `127.0.0.1:8091`，最终 Compose 不映射宿主端口；③ 浏览器 E2E 实际为 23 项检查（计划为 21 项）。

### M5 Docker Compose、一键部署与运维

- [x] 完成两个服务的 Dockerfile：
  - [x] 多阶段构建；
  - [x] BuildKit `/root/.m2` 缓存；
  - [x] 构建阶段执行各自模块测试；
  - [x] 运行阶段 JRE 8；
- [x] 完成 `docker-compose.yml`：
  - [x] `na-postgres`、`na-order-service`、`na-payment-service`；
  - [x] PostgreSQL 初始化脚本与健康检查；
  - [x] 支付服务不映射宿主端口；
  - [x] 订单服务依赖 PostgreSQL healthy，**不依赖支付服务健康**；
  - [x] 支付服务依赖 PostgreSQL healthy；
  - [x] 资源限制与 JVM 参数；
  - [x] 健康检查与重启策略；
- [x] 更新 `scripts/deploy.sh`：
  - [x] 预检 Docker / Compose / 8081；
  - [x] 识别本项目新服务是否已在运行；
  - [x] 首次生成 `.env`（数据库密码、内部 token）；
  - [x] `docker compose up -d --build --wait --wait-timeout 600`；
  - [x] 输出 `docker compose ps`；
- [x] 更新 `scripts/deploy.cmd`：路径、提示、失败暂停；
- [x] 新增 `scripts/backup.sh`：
  - [x] 停两个应用；
  - [x] `pg_dump` 两个 database；
  - [x] 恢复启动；
  - [x] 失败时明确提示；
- [x] 更新 `.dockerignore`、`.gitignore`（`.env`、备份文件）；
- [x] 切换 8081：
  - [x] 停止旧单体容器，保留旧镜像/旧卷；
  - [x] `deploy.cmd` 部署新栈；
  - [x] 验证两个容器 healthy；
  - [x] 验证 http://localhost:8081 与 Swagger；
- [x] 验证重启两个服务后订单/支付数据保留；
- [x] 验证备份与恢复；
- [x] 更新 README：架构、端口、运行、测试、部署、备份、已知限制；
- [x] 更新 `docs/design.md` / `docs/plan.md` 顶部链接到微服务设计/计划；
- [x] 在微服务设计文档追加“实现记录”。

**验收**

- [x] Windows 双击 `scripts\deploy.cmd` 一次成功；
- [x] 构建日志显示两个服务测试通过；
- [x] `na-order-service`、`na-payment-service`、`na-postgres` 全部 healthy；
- [x] 支付服务无宿主机端口；
- [x] 8081 页面、Swagger 可用；
- [x] 重启、备份、恢复验证通过；
- [x] 旧镜像/旧卷保留。

**M5 部署记录（2026-10-22）**

| 场景 | 结果 |
| --- | --- |
| `deploy.cmd` | 首次失败仅为批处理中文提示解析问题；改为 ASCII 输出后连续两次部署成功 |
| 构建测试 | 订单 32 + 支付 13 = 45 个测试通过，构建产物 `na-order-service.jar` / `na-payment-service.jar` |
| 容器状态 | 三个容器均 healthy；订单 `0.0.0.0:8081->8080`，支付仅 `8080/tcp`，PostgreSQL `127.0.0.1:5433` |
| 数据保留 | 重启两个应用后重新登录，20 笔历史订单与支付状态完整保留 |
| 备份 | `backups/na-orderdb-20261022-124539.dump`（15K）、`na-paymentdb-20261022-124539.dump`（6.2K） |
| 恢复 | 备份后新增订单 → `pg_restore --clean --if-exists` → 订单数回到 20，备份后订单 404，S5/S6 状态一致 |
| 干净库 E2E | 重建 schema 后 Flyway 重新迁移并种子化；Docker 栈上 23 项浏览器检查全部通过 |
| 回滚快照 | `order-system:local` 镜像与 `demo_order-data` 卷保留，旧容器 `demo-order-app-1` 已停止 |

**偏差**：Windows `cmd.exe` 在 UTF-8 代码页下会误解析部分中文批处理提示，`deploy.cmd` 输出统一改为 ASCII；不影响 `deploy.sh`、应用页面与文档。

### M6 总验收

- [x] 全量单元/集成测试通过；
- [x] 真实 PostgreSQL 上全量 E2E 通过；
- [x] 并发支付、并发抢购、取消/支付竞争通过；
- [x] 支付服务停机/恢复、读超时、崩溃恢复、对账通过；
- [x] 重启持久化、备份恢复通过；
- [x] 内部 token 401、用户越权 404、跨库访问拒绝通过；
- [x] 公开 API 与单体版本兼容，前端控制台无报错；
- [x] 模块边界检查：订单服务无支付实体/Repository，支付服务无订单/用户/商品类；
- [x] 无遗留 TODO、调试输出、硬编码密钥（演示默认值除外）；
- [x] 文档与实际实现一致，偏差记录完整；
- [x] 输出 M6 验收报告：交付物、测试结果、访问地址、演示账号、故障演练结果、已知限制。

**M6 验收报告（2026-10-22）**

#### 交付物

- Maven 工程：`na-payment-contract`、`na-order-service`、`na-payment-service`；
- Docker 服务：`na-postgres`、`na-payment-service`、`na-order-service`；
- 一键部署：`scripts/deploy.cmd` / `scripts/deploy.sh`；
- 备份：`scripts/backup.sh`；
- 文档：README、微服务设计、实施计划、单体历史设计与计划。

#### 自动化与浏览器验收

| 项目 | 结果 |
| --- | --- |
| `mvn clean package` | BUILD SUCCESS；订单 32 + 支付 13，共 45 个测试，0 失败 |
| Docker 构建 | 两镜像均在构建阶段执行测试并成功 |
| 浏览器 E2E | 干净 PostgreSQL 库上 23 项检查全部通过，控制台无报错；E2E 订单 `ORD20261022160939557603094` |
| 支付幂等 | 两个并发支付返回 200/201，同一支付单，1 条 payment，订单一次 PAID |
| 防超卖 | 最后 1 件商品 10 并发下单：1 成功、9 个 409「库存不足」，库存为 0 |
| 取消竞争 | 渠道 3s 时取消成功；支付 409，订单 CANCELLED，attempt/payment REFUNDED，库存回补 |
| 停机恢复 | 支付容器停止时 503、订单 PENDING、attempt `STARTED`；恢复并完全就绪后重试成功 |
| 读超时 | 渠道 7s、读取超时 5s：订单 503、attempt `STARTED`；支付最终 SUCCEEDED，对账约 20s 补 PAID |
| S5 崩溃恢复 | `STARTED + payment SUCCEEDED + PENDING` 对账补 PAID |
| S6 崩溃恢复 | `REFUND_PENDING + payment SUCCEEDED + CANCELLED` 对账退款成功 |
| 金额不一致 | 错误金额支付单不 markPaid，触发自动退款；退款超过读超时暂返 503/`REFUND_PENDING`，对账收敛为 `REFUNDED` |
| 对账 | 10s 周期、5s 陈旧阈值下全部收敛；正式 Compose 默认 60s |
| 重启 | 两应用容器重启后订单与支付状态保留；备份后再次重启仍保留 |
| 备份恢复 | `pg_dump` 两个 custom dump；恢复后历史数据一致，备份后新增数据被回退 |

#### 边界与安全

| 检查 | 结果 |
| --- | --- |
| 未登录 `/api/**` | 401 JSON |
| 内部 token 缺失/错误 | 401；有效 token 200 |
| 宿主访问支付服务 | 本地 8091 进程已停止；Docker 支付服务无宿主端口，仅内网 `na-payment-service:8080` 可达 |
| 跨用户访问 | 新建其他用户访问 demo 订单/支付均为 404 |
| 跨库访问 | `na_order_user` 对 `na_paymentdb` 无 CONNECT 权限 |
| 模块边界 | 订单源码无支付实体/Repository；支付源码无订单、用户、商品类 |
| 事务边界 | 静态检查确认支付客户端与渠道调用不在 `@Transactional` 方法内；服务使用短事务/条件更新 |
| 代码卫生 | 无 TODO/FIXME、`System.out`、`printStackTrace`；源码/Compose 无真实密钥，`.env` 仅本地保存 |

#### 部署与回滚快照

- 访问地址：<http://localhost:8081>；Swagger：<http://localhost:8081/swagger-ui.html>；
- 演示账号：`demo` / `123456`；
- 最终演示数据：12 笔订单（1 已支付、1 已取消），商品 7 库存 40、商品 8 库存 1；
- 旧容器 `demo-order-app-1` 已停止；旧镜像 `order-system:local`、旧卷 `demo_order-data` 保留；
- 旧源码快照：`backups/source-v1-20261022-112630.tar.gz`。

#### 已知限制

- 支付容器刚启动时可能出现极短的连接就绪竞态，首个重试返回 503，后续用户重试或对账可收敛；
- 金额不一致的支付单会按 `order_no` 唯一约束保留，订单后续合法支付会因已存在退款支付单而 409；该场景只能由内部 API 构造，正常编排不会产生；
- Mock 渠道、同步 Saga、单实例对账、共享 PG 实例与静态内部 token 的限制同设计文档第 14 节。

**验收**

- [x] DoD 全部勾选；
- [ ] 用户确认验收。

### 后续可选（不在本期）

- Outbox + RabbitMQ/Kafka 事件驱动；
- Spring Cloud Gateway、注册中心、Feign；
- ShedLock 多实例对账；
- mTLS/OAuth2 内部认证；
- 商品服务拆分；
- 真实支付渠道、财务对账、结算文件；
- Testcontainers/embedded PostgreSQL 进入构建测试；
- 多实例、读写分离、监控告警。

## 5. 文件改动清单

### 5.1 新增

| 区域 | 文件 |
|---|---|
| 父工程 | `pom.xml`（替换为 `na-order-platform` 父 POM） |
| 契约 | `na-payment-contract/pom.xml`、`src/main/java/com/example/na/payment/contract/{PaymentCommand,PaymentView,PaymentStatusView,RefundCommand,ClosePaymentCommand,InternalHeaders,PaymentErrorCode}.java` |
| 订单服务 | `na-order-service/pom.xml`、`Dockerfile`、启动类、`paymentclient/*`、`OrderPaymentOrchestrator`、`PaymentAttempt*`、`PaymentBffController`、`OrderPaymentReconciliationJob` |
| 支付服务 | `na-payment-service/pom.xml`、`Dockerfile`、启动类、`domain/*`、`repository/*`、`service/*`、`channel/*`、`web/*`、`config/Internal*`、`shared/*` |
| 数据库 | `na-order-service/src/main/resources/db/migration/V1__init_order_schema.sql`、`na-payment-service/src/main/resources/db/migration/V1__init_payment_schema.sql` |
| 配置 | 各服务 `application.yml`、`application-docker.yml`、`application-test.yml` |
| 部署 | `docker-compose.yml`、`.env.example`、`scripts/postgres/init-databases.sh`、`scripts/backup.sh` |
| 文档 | `docs/microservices-design.md`、`docs/microservices-plan.md`、README 架构章节 |
| 测试 | 各服务测试类、`PaymentClient` 契约测试、对账任务测试、E2E 脚本（临时或 `scripts/smoke.sh`） |

### 5.2 修改

| 区域 | 文件 |
|---|---|
| 订单代码 | 包名 `com.example.order` → `com.example.na.order`；删除支付相关类；订单取消逻辑保持本地事务；页面/静态资源仅移动 |
| 支付代码 | 包名迁移；移除 `OrderPaymentPort`；`MockPaymentGateway` → `MockPaymentChannel`；支付单增加 `currency / refunded_at / refund_reason` |
| 构建 | `.dockerignore`、`.gitignore`、`.gitattributes`（确保 WSL 脚本 LF） |
| 部署 | `scripts/deploy.sh`、`scripts/deploy.cmd` |
| 文档 | README、`docs/design.md`、`docs/plan.md` 顶部链接 |
| 测试 | `OrderServiceTest`、`PaymentServiceTest`、`SecurityTest` 适配新模块 |

### 5.3 删除

| 文件/目录 | 时机 |
|---|---|
| 旧 `src/main/java/com/example/order/payment/**` | M2 复制完成后 |
| `ordering/api/OrderPaymentPort*`、`PayableOrder*`、`OrderPaymentPortImpl` | M3 |
| 旧 `src/`（快照保留） | M4 |
| 根 `Dockerfile` | M5 由两个模块 Dockerfile 替代 |
| 旧 H2 运行时配置 | M3/M4 |
| 旧 `docker-compose.yml` 单服务定义 | M5 替换 |

## 6. API 与配置检查清单

### 6.1 内部 API

- [x] `POST /internal/v1/payments` 按 `orderNo` 幂等；
- [x] `GET /internal/v1/payments/{paymentNo}` 校验 userId；
- [x] `GET /internal/v1/payments/by-order/{orderNo}` 校验 userId；
- [x] `POST /internal/v1/payments/{paymentNo}/refund` 幂等；
- [x] `POST /internal/v1/payments/{paymentNo}/close` 幂等；
- [x] 全部要求 `X-Service-Token`；
- [x] 错误响应包含 `{code, message}`；
- [x] 请求 ID 透传并记录。

### 6.2 公开 API

- [x] `POST /api/payments` 行为、状态码、响应字段与单体一致；
- [x] `GET /api/payments/{paymentNo}` 行为一致；
- [x] 未登录 API 401 JSON；
- [x] 越权返回 404；
- [x] 支付不可用返回 503，订单保持 PENDING；
- [x] Swagger 仍有商品、订单、支付、用户分组。

### 6.3 配置

| 服务 | 变量 | 说明 |
|---|---|---|
| order | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://na-postgres:5432/na_orderdb` |
| order | `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | `na_order_user` |
| order | `APP_PAYMENT_BASE_URL` | `http://na-payment-service:8080` |
| order | `APP_PAYMENT_SERVICE_TOKEN` | 与支付服务一致 |
| order | `APP_PAYMENT_CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` | 2000 / 5000 |
| order | `APP_RECONCILIATION_ENABLED` | docker true，test false |
| payment | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://na-postgres:5432/na_paymentdb` |
| payment | `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | `na_payment_user` |
| payment | `APP_INTERNAL_TOKEN` | 与订单服务一致 |

## 7. 数据库迁移

- [x] `na_orderdb` 使用 Flyway 管理 users/products/orders/order_items/order_payment_attempt；
- [x] `na_paymentdb` 使用 Flyway 管理 payments；
- [x] 运行时 `ddl-auto=validate`；
- [x] 测试使用 H2 PostgreSQL 模式 + 同一套迁移脚本；
- [x] 迁移脚本避免 PostgreSQL 专属语法，保持 H2 可执行；
- [x] `DataInitializer` 继续幂等 seed 商品和 demo 用户，测试 profile 关闭；
- [x] 旧 H2 数据不迁移，旧卷保留；
- [x] 首次部署验证 Flyway 成功，`flyway_schema_history` 存在；第二次启动不重复执行。

## 8. 测试矩阵

| 编号 | 场景 | 层 | 预期 |
|---|---|---|---|
| T1 | 正常下单 | 订单 | 订单 PENDING，库存扣减 |
| T2 | 库存不足 | 订单 | 整单回滚 |
| T3 | 取消回补 | 订单 | 取消成功，库存恢复 |
| T4 | 重复取消 | 订单 | 不重复回补 |
| T5 | 正常支付 | 跨服务 | 支付 SUCCEEDED，订单 PAID |
| T6 | 顺序/并发重复支付 | 支付/跨服务 | 只有一笔支付单 |
| T7 | 非法状态流转 | 支付 | 409 |
| T8 | 越权订单/支付 | 订单/支付 | 404 |
| T9 | 并发抢购 | 订单 | 不超卖 |
| T10 | 未登录 API | 订单 | 401 JSON |
| S1 | 内部 token 校验 | 支付 | 缺失/错误 401 |
| S2 | 支付服务停机 | 跨服务 | 503，订单 PENDING，恢复后成功 |
| S3 | 读取超时/结果未知 | 跨服务 | attempt STARTED，对账收敛 |
| S4 | 支付/取消竞争 | 跨服务 | CANCELLED + REFUNDED，库存恢复 |
| S5 | 支付成功未 markPaid | 跨服务 | 对账补 PAID |
| S6 | 取消后未退款 | 跨服务 | 对账补 REFUNDED |
| S7 | 重复退款/关闭 | 支付 | 幂等 |
| S8 | 金额不一致 | 订单 | 不 markPaid，退款/失败 |
| S9 | 服务重启 | 部署 | 数据保留，状态一致 |
| S10 | 跨库访问 | 部署 | 账号无法访问对方 database |

### 8.1 测试命令

```powershell
# 全量构建 + 测试
.\mvnw.cmd clean package

# 单个服务
.\mvnw.cmd -pl na-payment-service -am test
.\mvnw.cmd -pl na-order-service -am test

# 本地 PostgreSQL
docker compose up -d na-postgres

# 本地服务（示例，具体 profile 在实现时固定）
.\mvnw.cmd -pl na-payment-service spring-boot:run -Dspring-boot.run.arguments="--server.port=8091"
.\mvnw.cmd -pl na-order-service spring-boot:run -Dspring-boot.run.arguments="--server.port=8090"
```

### 8.2 测试说明

- 自动化测试统一 `*Test` 结尾，Surefire 可扫描；
- 并发测试不使用测试级事务；
- H2 测试用于快速回归，真实 PostgreSQL 由 M4/M6 E2E 覆盖；
- 支付客户端测试使用 `MockRestServiceServer`，不引入额外 WireMock 依赖；
- 浏览器 E2E 沿用无头 Chrome，验证 21 项原有流程 + 新增故障场景；
- 故障注入使用测试专用 `app.mock.delay-ms`、停容器、手工 SQL 准备状态，不增加生产后门。

## 9. 完成定义（DoD）

- [x] `.\mvnw.cmd clean package` 成功，所有模块测试通过；
- [x] 两个服务可独立启动，health UP；
- [x] 仅订单服务暴露 8081，支付服务无宿主端口；
- [x] PostgreSQL 16 双 database、双账号、权限隔离；
- [x] Flyway 建表成功，`ddl-auto=validate` 通过；
- [x] 公开 API 与页面行为保持不变，前端无改动；
- [x] 支付 Saga 正常路径、重复支付、取消竞争全部通过；
- [x] 支付服务停机、超时、崩溃恢复、对账通过；
- [x] 订单服务无支付实体/Repository；支付服务无订单/用户/商品类；
- [x] 无跨库 SQL、无远程调用在事务内；
- [x] 内部 token 与越权测试通过；
- [x] 并发抢购不超卖、并发支付不重复扣款；
- [x] 重启后数据保留；备份/恢复成功；
- [x] 一键部署成功，构建日志包含测试通过；
- [x] README、设计、计划、实现记录一致；
- [x] 无 TODO、调试输出、硬编码密钥（演示默认值除外）；
- [x] 旧镜像/旧卷保留到验收后。

## 10. 风险与应对

| 风险 | 应对 |
|---|---|
| Flyway 8.5 / JDBC 42.3 对 PG 16 支持不足 | 固定 Flyway 9.22.x + JDBC 42.7.x；M1 验证；失败则降级 PostgreSQL 15 |
| H2 与 PG 方言差异 | 同一套 Flyway 脚本 + 真实 PG E2E；避免数据库专属 SQL |
| 单 PG 实例故障域 | 演示接受；生产拆两个实例 |
| 订单启动依赖支付导致全站不可用 | 订单只依赖 PostgreSQL；支付故障时仅支付接口 503 |
| 远程调用占用数据库事务 | 代码评审检查；编排/渠道调用均在事务外 |
| 对账任务单实例 | 本期单实例；多实例前加 ShedLock |
| 静态内部 token 泄露 | 私网 + 不暴露端口；生产 mTLS/OAuth2 |
| 渠道幂等假设 | 本期 Mock；真实渠道接入前确认幂等能力 |
| 退款失败 | `REFUND_PENDING` + 对账重试 + 告警 |
| 旧容器占用 8081 | M5 切换前停止旧容器，保留镜像/卷 |
| `.env` 修改后数据库密码不生效 | 首次初始化后修改需 `ALTER ROLE`；文档注明 |
| WSL 脚本 CRLF | `.gitattributes` / 写入时保持 LF；`deploy.cmd` 使用 `bash` 调用 |
| Windows 挂载卷 PostgreSQL 性能 | 数据卷使用 Docker named volume，不挂 `/mnt/e` |
| 首次构建慢 | BuildKit Maven 缓存；`go-offline` 兜底；文档注明 |

## 11. 回滚方案

1. M0 源码快照可从 `backups/` 解压恢复；
2. 旧镜像 `order-system:local` 与旧卷 `demo_order-data` 保留；
3. M5 切流前停止旧容器，不删除；
4. 新栈异常时：
   - 停止 `na-order-service`、`na-payment-service`；
   - 用旧 compose/镜像恢复旧单体到 8081；
   - 旧数据卷未被新栈写入，订单/支付历史仍在；
5. 切流后新产生的数据不会自动回到旧 H2；回滚需接受新数据丢失或单独导出；
6. 新 PostgreSQL 卷 `na-pg-data` 保留，便于修复后重新上线。

## 12. 汇报点

- **M2 完成**：支付服务独立启动、内部 API 与测试结果；
- **M4 完成**：双服务本地联调、Saga/补偿/对账、故障场景结果；
- **M6 完成**：一键部署、全量验收、访问地址、已知限制。

## 13. 评审重点

请重点确认：

1. `na-` 前缀覆盖范围是否符合你的命名约定；
2. 是否接受 `order_payment_attempt` 这张 Saga 日志表；
3. 是否接受公开 API 不变、支付服务完全不对外暴露；
4. 是否接受 M1–M4 期间 8081 继续跑旧单体、M5 才切换；
5. 是否接受同步 Saga，不引入 MQ；
6. 是否接受旧数据删除、不迁移；
7. 是否接受 `.env` 自动生成随机密码与内部 token。

确认后按 M0 开始实施；每个里程碑完成后汇报结果，再进入下一个。
