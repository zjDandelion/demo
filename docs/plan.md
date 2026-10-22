# 简易订单系统 · 实施计划

| 项目 | 内容 |
|---|---|
| 版本 | v1.0（2026-10-22，已完成） |
| 日期 | 2026-10-22 |
| 配套文档 | [设计方案](design.md) |
| 微服务改造 | [微服务设计](microservices-design.md) · [微服务实施计划](microservices-plan.md) |
| 状态 | 单体版本已完成；微服务版本按新计划执行 |
| 执行方式 | 按里程碑顺序推进，每个里程碑自测通过后再进入下一个 |

> 三轮审查的修订记录见[设计方案](design.md)第 18 节；实现结果与细微澄清见第 19 节。

## 1. 目标与交付物

完成后在 `E:\zhaoo_workspace\idea_workspace\demo` 交付：

1. 可运行的 Spring Boot 2.7.18 / JDK 8 订单系统源码；
2. 自动化测试（订单、库存、支付、权限、并发）；
3. 内置网页：登录、商品、结算、订单列表/详情；
4. Swagger 接口文档；
5. Dockerfile + docker-compose.yml，可在 WSL2 Ubuntu 一键启动；
6. `README.md`：本地运行、测试、部署、演示账号说明；
7. 设计与计划文档随实现同步更新。

## 2. 执行原则

- 小步提交式推进：每完成一个里程碑先跑测试/启动验证，再做下一个；
- 不为假想需求加组件（无 Redis、MQ、分布式事务、微服务注册中心）；
- 模块边界从第一行代码开始执行：payment 不碰订单表，ordering 不碰商品表；
- 所有金额、库存、订单状态以服务端为准；
- 事务方法内不吞业务异常：失败必须抛出以触发回滚，再由 `GlobalExceptionHandler` 转成 HTTP 响应；
- 发现设计与实际冲突时，先停下说明并更新文档，不默默偏离。

## 3. 里程碑总览

| 里程碑 | 内容 | 依赖 | 关键验收 |
|---|---|---|---|
| M0 | 工程骨架与运行环境 | - | 应用启动、health UP、H2 建库 |
| M1 | 领域模型、Repository、种子数据 | M0 | 表结构正确、种子幂等 |
| M2 | 登录与安全 | M1 | 登录/退出、API 401、页面重定向 |
| M3 | 商品浏览 | M2 | 商品页展示、库存正确 |
| M4 | 下单、取消、防超卖（核心） | M3 | 订单闭环 API + 并发测试通过 |
| M5 | 支付模块（独立边界） | M4 | 支付成功/幂等/竞态测试通过 |
| M6 | 前端完整交互 | M5 | 浏览器端到端全流程 |
| M7 | Swagger、错误处理、README | M6 | 文档可调试、说明可复现 |
| M8 | 一键构建、部署与总验收 | M7 | 一键构建含测试、8081 可访问、重启数据保留 |

## 4. 详细任务

### M0 工程骨架与运行环境

- [x] `pom.xml`：Boot 2.7.18 父工程，`java.version=8`、UTF-8，`finalName=order-system`
  - `spring-boot-maven-plugin`（`repackage`，生成可执行 jar）
  - web、thymeleaf、security、data-jpa、validation、actuator
  - H2（runtime）、springdoc-openapi-ui 1.7.0、thymeleaf-extras-springsecurity5
  - lombok、test、security-test
- [x] Maven Wrapper（`mvnw.cmd`、`.mvn/wrapper/*`）。本机无 Maven，用已拉取的 Maven 镜像生成：
  ```powershell
  wsl.exe -d Ubuntu -- bash -lc 'cd /mnt/e/zhaoo_workspace/idea_workspace/demo && docker run --rm -v "$PWD":/work -w /work maven:3.9-eclipse-temurin-8 mvn -N wrapper:wrapper -Dmaven=3.9.9'
  ```
  失败时用 IDEA 内置 Maven 执行 `wrapper:wrapper`；Docker 部署不依赖 wrapper。
- [x] Java 8 语法约束：不用 `var`、`record`、`List.of`/`Map.of`、文本块、`String.isBlank()`、`Optional.isEmpty()`、`Stream.toList()`
- [x] `.gitignore`：`target/`、`data/`、`backups/`、IDE 文件
- [x] `.gitattributes`：`*.sh`/`mvnw` 强制 LF，`*.cmd` 用 CRLF，避免 WSL 执行脚本时报 `\r` 错误
- [x] `src/test/resources/application-test.yml`：H2 内存库（`${random.uuid}` 独立库）、`create-drop`、`LOCK_TIMEOUT=10000`、连接池 20、`app.seed.enabled=false`
- [x] `OrderApplication.java`
- [x] `application.yml`、`application-docker.yml`（数据源、JPA、时区、actuator health）
- [x] 启动验证

**验收**

```powershell
.\mvnw.cmd -v
.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=8090
# GET http://localhost:8090/actuator/health → {"status":"UP"}
```

`./data/orderdb.mv.db` 生成，应用无异常退出。本地开发固定用 8090（8080 被现有 sub2api 占用，8081 留给容器映射）。

### M1 领域模型、Repository、种子数据

- [x] 实体与枚举：`User`、`Product`、`CustomerOrder`、`OrderItem`、`OrderStatus`、`Payment`、`PaymentStatus`
  - 订单实体类命名为 `CustomerOrder`，避免 HQL 保留字 `ORDER`；表名仍为 `orders`
- [x] Repository：
  - `ProductRepository`：条件扣库存、回补库存
  - `OrderRepository`：条件取消、条件标记支付、按用户分页
  - `PaymentRepository`：按 `order_no` 查询
- [x] `DataInitializer`：8 个商品（其中一个库存 1）；`app.seed.enabled` 控制（测试 profile 关闭）；幂等，不覆盖已有数据
- [x] 表名使用 `users` / `orders`，规避 H2 保留字

**验收**

- 启动后自动建表、写入商品种子数据；
- 重复启动不重复插入；
- 商品价格、库存符合种子定义。

### M2 登录与安全

- [x] `SecurityConfig`：`SecurityFilterChain`（不继承已废弃的 `WebSecurityConfigurerAdapter`）、表单登录、公开路径、其余认证、BCrypt、SameSite
- [x] `/api/**` 未登录返回 401 JSON；页面未登录重定向 `/login`
- [x] 登录成功只允许站内相对跳转，防开放重定向
- [x] `CustomUserDetailsService`，返回携带 `userId` 的 `UserDetails`，业务层不再按用户名反查
- [x] `PasswordEncoder`（BCrypt）；`DataInitializer` 增加演示账号，账号密码由 `DemoUserProperties` 覆盖，仅 `users` 为空时创建（同样受 `app.seed.enabled` 控制）
- [x] `login.html`、基础布局片段、`app.css` 初版
- [x] 退出登录
- [x] `PageController` 页面路由骨架
- [x] CSRF：登录/退出表单开启；`/api/**` 按设计关闭（评审项 Q1）
- [x] 测试 T10（`SecurityTest`，MockMvc 断言 401 JSON 与页面重定向）

**验收**

- 未登录访问 `/` → 登录页；
- 未登录 `GET /api/products` → 401 JSON；
- 正确账号登录成功，错误密码有提示；
- 演示账号密码以 BCrypt 存储；
- 退出后无法访问受保护页面。

### M3 商品浏览

- [x] `catalog/api/CatalogPort` + `ProductInfo`
- [x] `ProductService`（实现 `CatalogPort`）、`ProductController`、`ProductView`
- [x] `index.html` 商品网格，`products.js`、`api.js`、`cart.js`
- [x] 库存为 0 显示售罄且不可加购
- [x] 购物车角标（localStorage）；损坏数据解析失败时清空，不让页面崩溃

**验收**

- 登录后商品列表正确展示名称、价格、库存；
- 加购/改数量/删除正常，刷新后购物车保留；
- 售罄商品无法加购。

### M4 下单、取消、防超卖（核心）

- [x] `ordering` 领域实体、Repository、DTO
- [x] `OrderService.createOrder`：
  - 参数校验、重复商品合并、按 productId 排序
  - 逐项原子扣库存，失败整单回滚
  - 服务端计价、快照、订单号、状态 `PENDING_PAYMENT`
- [x] `OrderService.list/detail/cancel`；越权 404
- [x] `OrderNoGenerator`：毫秒时间戳 + 6 位随机数，唯一索引兜底，不在事务内重试
- [x] `OrderController`、`PageResponse`、订单 DTO
- [x] `OrderPaymentPort` 契约 + 本地实现（`loadForPayment`、`markPaid`）
- [x] `GlobalExceptionHandler`、业务异常、统一错误响应；行锁等待/唯一约束冲突映射 409，不暴露 500
- [x] `checkout.html`、`checkout.js`：刷新价格/库存、提交、失败提示、成功后清空购物车
- [x] `orders.html`、`order-detail.html` 最小页面壳，`orders.js`、`order-detail.js` 提供列表、详情、取消入口；支付按钮在 M5 接入
- [x] 测试 T1、T2、T3、T4、T8（订单越权）、T9，统一放在 `OrderServiceTest`
- [x] T9 不加 `@Transactional`：种子数据先提交，用 `CountDownLatch` 同时起跑，断言成功数=1、库存=0、订单数=1

**验收**

- 多商品下单金额正确、库存正确扣减；
- 第二件商品库存不足时：返回 409，第一件库存不变、无订单落库；
- 取消订单回补库存，重复取消 409 且不重复回补；
- 并发测试：10 线程抢 1 件，仅 1 单成功，库存 0；
- 所有测试通过。

**评审点**：此处完成后汇报一次核心链路结果，再进入支付。

### M5 支付模块（独立边界）

- [x] `payment` 实体、Repository、DTO、状态机
- [x] `PaymentService`：
  - 先 `loadForPayment` 校验订单存在与归属（不存在/越权 404），再查已有支付单
  - 已有 `SUCCEEDED` 支付单 → 直接返回（幂等）；已有非成功支付单 → 409
  - 订单状态非 `PENDING_PAYMENT` → 409
  - 创建 `Payment(CREATED)`（金额取订单总额快照）→ Mock 网关成功 → `markPaid` → 支付单 `SUCCEEDED`
  - `markPaid` 更新 0 行（已被取消/支付）→ 抛冲突，订单与支付单同一事务回滚，不保留支付单，返回 409
- [x] `MockPaymentGateway`：同步返回成功，后续可替换为真实渠道
- [x] `markPaid` 的批量更新只加 `flushAutomatically`、不加 `clearAutomatically`，避免把同一事务里的 `Payment` 实体 detached
- [x] `PaymentController`：`POST /api/payments`、`GET /api/payments/{paymentNo}`（越权 404）
- [x] 测试 T5、T6（顺序 + 并发）、T7、T8（支付越权），放在 `PaymentServiceTest`
- [x] 测试统一使用 M0 建立的 `test` profile（H2 内存、`create-drop`、连接池 20）
- [x] 订单页/详情页支付按钮（最小交互）

**验收**

- 支付后订单 `PAID`、支付单 `SUCCEEDED`；
- 重复支付返回同一支付单，只有一条支付记录；
- 已取消订单不能支付，已支付订单不能取消；
- 已取消订单支付返回 409，且不产生新的 payments 记录（事务回滚）；
- payment 模块无任何订单 Repository/实体引用。

### M6 前端完整交互

- [x] `/orders`：分页、状态筛选、支付/取消、结果刷新
- [x] `/orders/{orderNo}`：明细、金额、状态、操作
- [x] 通用提示（成功/失败 toast）、加载态、空状态
- [x] fetch 统一处理 401 跳转登录，403/409 显示可读错误
- [x] 移动端适配、状态徽章、购物车角标同步
- [x] 用户数据统一 `textContent` 渲染，避免 XSS
- [x] 退出、导航、当前用户展示；退出时清空本地购物车

**验收**

- 浏览器完整走通：登录 → 加购 → 结算 → 下单 → 支付；
- 另下一单 → 取消 → 库存回补；
- 刷新/翻页/筛选后状态一致；
- 无控制台报错。

### M7 Swagger、错误处理、README

- [x] `OpenApiConfig`，接口分组与必要注解
- [x] `/swagger-ui.html` 可访问，登录后可带 Session 调试
- [x] 校验错误、业务错误、未登录错误格式统一；`/api/**` 返回 JSON，页面请求按页面方式处理
- [x] `README.md`：功能、账号、本地运行（8090）、测试、一键 Docker 部署（8081）、目录说明、已知限制
- [x] README 明示：H2 单实例、`/api/**` 关闭 CSRF、重复下单无服务端幂等、仅本机/内网演示
- [x] 更新设计与计划文档，记录实现过程中的偏差
- [x] 清理死代码、TODO、调试输出

**验收**

- Swagger 能读取全部接口并成功调用一个写接口；
- README 按步骤可从零启动；
- 错误响应符合设计文档第 8 节。

### M8 一键构建、部署与总验收

- [x] 多阶段 `Dockerfile`：Maven 3.9 + JDK 8 构建，JRE 8 运行
- [x] 构建阶段执行全部测试（`mvn -B package`），测试失败则构建失败
- [x] BuildKit Maven 依赖缓存：`/root/.m2` cache mount
- [x] `go-offline` 只预热缓存，失败不阻断（`|| true`），真正依赖在 `package` 阶段解析
- [x] `.dockerignore`：`target/`、`data/`、`backups/`、`.git/`、`docs/`
- [x] `docker-compose.yml`：`build: .`、`8081:8080`、数据卷、健康检查（10s/3s/10 次/start_period 30s）、`mem_limit: 1g`、重启策略
- [x] `application-docker.yml` 数据源指向 `/app/data`，带 `LOCK_TIMEOUT=10000`
- [x] `scripts/deploy.cmd`（Windows 双击）与 `scripts/deploy.sh`（WSL 内执行），预检 `docker info`、`docker compose version`、8081 端口
- [x] 一条命令完成构建、测试、打包、启动：`docker compose up -d --build --wait --wait-timeout 300`
- [x] 容器 health 通过；Windows `http://localhost:8081` 可访问
- [x] 构建日志中的 Surefire 汇总确认 T1–T10 实际执行（tests 数 > 0）
- [x] 测试失败时不替换旧容器
- [x] 重启容器后订单数据保留
- [x] 验证备份流程：停容器 → 打包 `order-data` 卷 → 启动
- [x] 重跑全量测试与并发测试
- [x] 最终演示走查

**验收**

```powershell
.\scripts\deploy.cmd
```

```bash
docker compose ps          # order-app healthy
curl http://localhost:8081/actuator/health
docker compose restart order-app
# 数据仍在
```

Jenkins 不在本期范围；服务稳定后作为独立阶段评估。

## 5. 文件清单（预计）

| 区域 | 文件 |
|---|---|
| 构建 | `pom.xml`、`mvnw`、`mvnw.cmd`、`.mvn/wrapper/*`、`.gitignore`、`.gitattributes`、`.dockerignore` |
| 配置 | `application.yml`、`application-docker.yml` |
| 启动 | `OrderApplication.java` |
| config | `SecurityConfig`、`OpenApiConfig`、`DataInitializer`、`DemoUserProperties`、`PageController` |
| shared | `BusinessException`、`GlobalExceptionHandler`、`ErrorResponse`、`PageResponse` |
| catalog | `Product`、`ProductRepository`、`CatalogPort`、`ProductInfo`、`ProductService`、`ProductController`、`ProductView` |
| user | `User`、`UserRepository`、`CustomUserDetailsService` |
| ordering | `CustomerOrder`、`OrderItem`、`OrderStatus`、`OrderRepository`、`OrderService`、`OrderPaymentPort`、`PayableOrder`、`OrderPaymentPortImpl`、`OrderController`、订单 DTO、`OrderNoGenerator` |
| payment | `Payment`、`PaymentStatus`、`PaymentRepository`、`PaymentService`、`MockPaymentGateway`、`PaymentController`、支付 DTO |
| 页面 | `templates/login.html`、`index.html`、`checkout.html`、`orders.html`、`order-detail.html`、布局片段 |
| 静态资源 | `static/css/app.css`、`static/js/api.js`、`cart.js`、`products.js`、`checkout.js`、`orders.js`、`order-detail.js` |
| 测试 | `OrderServiceTest`、`PaymentServiceTest`、`SecurityTest`、`src/test/resources/application-test.yml` |
| 部署 | `Dockerfile`、`docker-compose.yml`、`.dockerignore`、`scripts/deploy.cmd`、`scripts/deploy.sh` |
| 文档 | `README.md`、`docs/design.md`、`docs/plan.md` |

## 6. 测试与验收矩阵

| 测试 | 对应里程碑 | 自动化 |
|---|---|---|
| T1 正常下单 | M4 | 是 |
| T2 库存不足整单回滚 | M4 | 是 |
| T3 取消回补库存 | M4 | 是 |
| T4 重复取消不重复回补 | M4 | 是 |
| T5 支付成功 | M5 | 是 |
| T6 重复支付幂等（顺序 + 并发） | M5 | 是 |
| T7 非法状态流转 | M5 | 是 |
| T8 越权订单/支付单 404 | M4/M5 | 是 |
| T9 并发抢购不超卖 | M4 | 是 |
| T10 未登录 API 401 | M2 | 是 |
| E2E 浏览器全流程 | M6/M8 | 手工 + 截图 |
| Docker 重启数据保留 | M8 | 手工 |

测试类统一以 `*Test` 结尾（Surefire 默认不扫描 `*IT`）；测试统一激活 `test` profile；T9 方法不加 `@Transactional`，数据准备需先提交。

测试与构建命令：

```powershell
# Windows 本地
.\mvnw.cmd clean test
.\mvnw.cmd clean package

# WSL 容器内构建（同时执行测试）
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/e/zhaoo_workspace/idea_workspace/demo && docker compose build"
```

## 7. 完成定义（DoD）

- [x] `.\mvnw.cmd clean package` 成功，全部自动化测试通过；
- [x] Surefire 报告显示 T1–T10 均有执行（tests 数 > 0，而非 0 tests 的假成功）；
- [x] 测试类统一 `*Test`；并发测试未使用测试级事务；
- [x] 一键部署成功，构建日志显示测试通过；
- [x] 浏览器端到端流程全部通过，控制台无报错；
- [x] 并发测试证明不超卖；
- [x] 容器 healthy，重启后数据保留；
- [x] 订单/支付模块无越界访问（人工检查依赖）；
- [x] README 可按步骤复现；
- [x] 设计文档与实际实现一致（偏差已记录）；
- [x] 无遗留 TODO、调试输出、硬编码密钥（演示账号默认值除外，README 已标注）；
- [x] 评审问题 Q1–Q6 已确认或按默认执行。

## 8. 风险与应对

| 风险 | 应对 |
|---|---|
| Maven Wrapper 下载失败 | 用 Maven 镜像生成 wrapper；失败则用 IDEA 内置 Maven 或直接 Docker 构建（部署不依赖 wrapper） |
| H2 保留字导致建表失败 | 已固定 `users` / `orders`；建表后立即启动验证 |
| 并发测试不稳定 | 使用 CountDownLatch，断言"成功数=1、库存=0、订单数=1"而非顺序 |
| Boot 2.7 + springdoc 版本兼容 | 固定 springdoc 1.7.0；不引入 Boot 3 专用配置 |
| `/mnt/e` 构建上下文慢 | 只复制 `pom.xml` 与 `src`，`.dockerignore` 排除无关文件；Maven 在容器内运行 |
| 8080 被 sub2api 占用 | compose 固定 `8081:8080`；本地开发用 8090 |
| Docker Hub 直连超时 | 使用 daemon 已配置的 `docker.1ms.run` 加速器；JDK8/Maven 镜像已拉取验证 |
| 首次构建慢 | Maven 依赖使用 BuildKit cache mount，后续增量构建复用 |
| 测试失败导致部署中止 | 预期行为：旧容器继续运行，修复测试后再部署 |
| 新镜像启动失败 | 构建成功后旧容器可能已被替换；数据卷仍在，查日志修复后重新部署，不做自动回滚 |
| 测试类命名导致假成功 | 统一 `*Test`；DoD 检查 Surefire tests 数 |
| H2 并发锁等待超时 | URL 加 `LOCK_TIMEOUT=10000`，测试连接池 20 |
| 重复下单 | 前端防重复点击；演示范围不做服务端幂等，README 注明限制 |
| Docker daemon/Compose 未就绪 | 部署脚本预检并输出明确错误 |
| `go-offline` 依赖缓存不完整 | 构建阶段在线补下载；保证 Maven Central 可达 |
| 容器内存过紧被 OOM Kill | `mem_limit: 1g` 配 `-Xmx512m`，预留 Metaspace/线程/直接内存 |
| 容器重启会话丢失 | Session 存内存，重启后重新登录；演示接受，多实例前引入 Spring Session |
| WSL 未自动启动 | 文档提供计划任务方案，不默认强依赖 |
| Swagger 写接口 403 | API 关闭 CSRF（Q1）；若改为开启，需自定义 Swagger 请求头 |
| 演示账号过弱 | 默认仅本机/可信局域网；公网前强制改密并加 HTTPS |

## 9. 待确认事项

与设计方案第 17 节一致，默认值已可执行：

| # | 事项 | 默认 |
|---|---|---|
| Q1 | `/api/**` 关闭 CSRF 以支持 Swagger | 是 |
| Q2 | 演示账号配置化，默认 `demo/123456` | 是 |
| Q3 | 不做支付失败模拟 | 是 |
| Q4 | 仅本机/局域网演示，不做公网暴露 | 是 |
| Q5 | 不增加"已完成"订单状态 | 是 |
| Q6 | 商品列表不分页/不搜索 | 是 |

若评审无修改，将按默认值执行。

部署方式已确认：本期采用 WSL 多阶段构建 + 一键脚本（宿主机 8081，构建阶段自动跑测试）；Jenkins 留到服务稳定后作为独立阶段评估。

## 10. 汇报与评审节点

1. **现在**：评审设计与本计划，确认 Q1–Q6；
2. **M0–M1 完成**：汇报工程可启动、表结构与种子数据；
3. **M4 完成**：汇报核心下单/防超卖测试结果（关键节点）；
4. **M8 完成**：交付完整演示、测试结果、部署地址与使用说明。

后续如需调整范围（如公网部署、支付失败模拟、数据库替换），先更新设计与计划再实施。
