# 已知问题清单

> 来源：`docs/microservices-design.md` §17.5 交付后架构审查（2026-10-22），2026-10-27 逐条回代码复核。
> 仅收录**代码可验证**的问题；README「已知限制」中的设计边界另列在文末。
> 状态列全部为「未修复」的条目才需要排期。

## 已修复（留档）

| ID | 问题 | 修复位置 | 回归用例 |
|---|---|---|---|
| P0-1 | `FAILED` 的支付尝试复位时状态守卫误用 `STARTED_ONLY`，导致订单永久卡在待支付、库存不回补 | `OrderPaymentAttemptStore.resume()` 改为 `FAILED_ONLY` 守卫 | `OrderPaymentOrchestratorTest.failedAttemptIsRetriedByNextPayment` |
| P0-2 | 内部鉴权按未解码的 `getRequestURI()` 前缀判断，`/%69nternal/...`、`//internal/...` 可无 token 访问支付接口 | `InternalTokenFilter` 改为拒绝优先，仅精确放行 `/actuator/health`、`/error` | `InternalAuthBypassTest`（真实 Tomcat，MockMvc 复现不了解码差异） |
| KI-16 | `AGENTS.md` 的模块清单只写三个 Maven 模块，未预留 Python 服务的位置 | `AGENTS.md` 已补 `na-assistant-service`（非 Maven 模块）与 pytest/Docker 说明 | `na-assistant-service/tests/`、`AssistantContractTest` |

## P1：正确性与安全，建议优先处理

| ID | 问题 | 证据（代码位置） | 影响 | 建议 |
|---|---|---|---|---|
| KI-01 | 渠道调用无幂等键，且支付单无 `PROCESSING` 中间态 | `na-payment-service/.../channel/MockPaymentChannel.java:21`；`PaymentStatus` 只有 CREATED/SUCCEEDED/FAILED/REFUNDED | Mock 渠道看不到问题，接真实渠道后超时重试可能重复扣款、重复退款 | 渠道请求带 `paymentNo` 作幂等键；调渠道前先落 `PROCESSING`，结果回写 |
| KI-02 | `refund`/`close` 先读状态 → 事务外调渠道 → 再条件更新，中间无 CAS 认领 | `PaymentService.java:87`、`:108` | 并发两次退款可同时通过前置状态检查，都可能打到渠道 | 先用条件 UPDATE 把状态认领为 `REFUNDING`/`CLOSING`，抢不到直接返回 |
| KI-03 | `refund`/`close` 不校验 `userId` 归属 | `PaymentService.refund(String paymentNo, String reason)`、`close(...)` 签名不含 userId；`InternalPaymentController.java:59`、`:69` | 任何持有内部 token 的调用方可退/关他人支付单 | 命令对象补 `userId`，服务端比对支付单归属 |
| KI-04 | 对账 `STATUS_SUCCEEDED` 分支不校验金额与用户 | `OrderPaymentReconciliationJob.java:106-115` 直接 `completePaid`；同类的 `retryPayment` 却校验金额 | 支付服务返回错金额/错用户的异常数据时，会被直接置为已支付 | 抽出统一的 `assertPaymentMatchesOrder`，编排器与对账共用 |
| KI-05 | `/api/**` 关闭了 CSRF，但认证依赖会话 Cookie | `config/SecurityConfig.java:65`（`.ignoringAntMatchers("/api/**")`） | 跨站页面可借用户会话代发下单/取消/支付请求 | 前端改带 CSRF token（或双提交 Cookie）；至少先显式设置 `SameSite` 并复核 |
| KI-06 | `.env` 与 `backups/*.dump` 权限过宽（实测 0644） | 运行环境，`scripts/backup.sh` 生成备份后未收紧 | 同机其他用户可读到数据库口令与业务数据快照 | `umask 077`；备份落盘后 `chmod 600` |
| KI-07 | `backup.sh` 的 `trap cleanup EXIT` 早于 postgres 预检 | `scripts/backup.sh:21`（trap）vs `:25`（预检） | postgres 没起时预检直接退出，cleanup 反而把两个应用容器拉起来 | trap 挪到预检之后，或 cleanup 里判断是否真的停过服务 |
| KI-08 | 未开优雅停机 | 两个 `application.yml` 均无 `server.shutdown` | 重启/滚动更新会硬切正在执行的 Saga，放大不一致窗口 | 加 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase` |

## P2：可维护性与工程化

| ID | 问题 | 证据（代码位置） | 建议 |
|---|---|---|---|
| KI-09 | 支付状态、订单状态、内部 API 路径、错误码未下沉到 `na-payment-contract` | `PaymentStatus` 在支付模块，`OrderStatus` 在订单模块，路径硬编码在 `PaymentClient` | 枚举与路径常量进契约模块，两端共同依赖 |
| KI-10 | `PaymentClient` 不解析响应体 `code`，只按 HTTP 状态映射 | `PaymentClient.java:114-129`；401/403 统一成「内部服务认证失败」 | 解析 `code`，区分「认证失败」与「暂时不可用」，便于排障 |
| KI-11 | 对账任务无分布式锁 | `OrderPaymentReconciliationJob.reconcile()` 为 `@Scheduled` | 多实例部署前引入 ShedLock 或数据库锁行 |
| KI-12 | 全部测试只跑 H2，生产 PostgreSQL 的锁等待/隔离级别未被门禁覆盖 | 两个 `application-test.yml`；无 Testcontainers | 加 Testcontainers-PostgreSQL16 的集成测试子集；生产 URL 显式设 lock/statement timeout |
| KI-13 | 容器以 root 运行 | 两个模块 `Dockerfile` 无 `USER` | 建非特权用户并 `USER` 切换 |
| KI-14 | `listOrders` 存在 N+1 查询 | `OrderService.java:95` 调用 `order.getItems().size()`；`CustomerOrder.items` 为 LAZY 且无 `@BatchSize`/`@EntityGraph` | 改为聚合计数查询或 `@EntityGraph` 只取需要的列 |
| KI-15 | 一个订单只允许一笔支付，进入终态后无法再次支付 | `V1__init_payment_schema.sql:15`（`uk_payments_order_no`） | 若要支持重试，需改为「一单多支付单 + 有效单一笔」模型，属产品决策 |
| KI-17 | 仓库根 `Dockerfile` 是失效的旧单体文件 | `COPY src ./src`、`target/order-system.jar` 均不存在，compose 也不引用 | 删除 |

## 设计边界（非缺陷，不做即为已知取舍）

- 同步 Saga 无 MQ：服务不可用时依赖重试 + 对账收敛，极端情况有短暂不一致。
- 支付渠道为 Mock、同步成功；接入真实渠道前须先闭环 KI-01。
- 演示环境两个服务共用一个 PostgreSQL 实例。
- 内部认证为静态共享 token，生产应在私网隔离基础上替换为 mTLS/OAuth2。
- 对账按单实例设计（对应 KI-11）。
- 旧单体镜像 `order-system:local`、旧卷 `demo_order-data` 保留作回滚快照；旧数据按约定未迁移。

## 建议的修复顺序

1. **KI-03 + KI-04**：都是「少一个校验」，改动小、直接消除越权与错误入账，一天内可完成并补测试。
2. **KI-02 + KI-01**：退款/关闭的 CAS 认领与 `PROCESSING` 中间态一起做，接真实渠道前必须完成。
3. **KI-05 + KI-06 + KI-07 + KI-08**：安全与运维卫生，可打包成一批。
4. **KI-09 ~ KI-17**：工程化清理，随下次功能开发顺带处理。
