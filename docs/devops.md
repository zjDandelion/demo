# DevOps 操作手册

本项目采用**零成本**方案：GitHub Actions（CI）+ GHCR（镜像仓库）+ WSL 本地 Docker（运行环境）+ Uptime Kuma（监控）。

## 1. 日常操作

| 场景 | 命令 | 说明 |
|---|---|---|
| 启动整套服务 | 双击 `scripts\start.cmd` | 唤醒 WSL → 等 Docker → 起容器 → 等健康 → 打开浏览器 |
| 改代码后重建 | `scripts\start.cmd --build` | 同上，先 `docker compose build` |
| 停止服务 | 双击 `scripts\stop.cmd` | 保留数据卷 |
| 看日志 | `wsl -d Ubuntu` 后 `docker compose logs -f na-order-service` | |
| 监控面板 | <http://localhost:3001> | Uptime Kuma，首次访问创建账号 |
| 容器管理 | Portainer | 已在运行，查看状态/日志/重启 |

## 2. 发布流程

```
改代码 → git push → GitHub Actions 跑测试（红则不能合并）
                  → git tag v1.2.3 && git push --tags
                  → Release 工作流推镜像到 GHCR
                  → WSL 里执行 bash scripts/pull-deploy.sh v1.2.3
```

首次发布前需要：

1. 仓库 Settings → Actions → General → Workflow permissions 选 **Read and write**（推送 GHCR 需要）；
2. 首次 `docker pull ghcr.io/...` 需要在 WSL 里登录：
   ```bash
   echo <你的GitHub PAT> | docker login ghcr.io -u zjDandelion --password-stdin
   ```
   PAT 只需 `read:packages` 权限（拉取）或 `write:packages`（手动推送）。

## 3. 回滚

```bash
bash scripts/rollback.sh          # 回到上一个成功版本
bash scripts/rollback.sh v1.2.2   # 回到指定版本
```

部署历史记录在 `.deploy-history`（最近 10 条），`pull-deploy.sh` 成功后才写入。

**注意**：回滚只回退代码/镜像，**不回退数据库 schema**。破坏性迁移（删列、改类型）必须拆成两步：先发兼容版本，确认无流量后再清理。

## 4. 备份与恢复

自动：WSL cron 每天 03:00 执行 `scripts/backup.sh`，日志在 `~/.local/state/na-backups/backup.log`。

手动：
```bash
bash scripts/backup.sh
```

- 备份位置：`~/na-backups/`（WSL 原生文件系统，权限 700/600）
- **不要放在 `/mnt/e`**：9p 挂载下 `chmod` 无效，权限恒为 777
- 保留最近 7 组（`BACKUP_KEEP_DAILY` 可调）

恢复：
```bash
docker compose stop na-order-service na-payment-service
docker compose exec -T na-postgres pg_restore -U na_order_user -d na_orderdb \
  --clean --if-exists < ~/na-backups/na-orderdb-<时间戳>.dump
docker compose exec -T na-postgres pg_restore -U na_payment_user -d na_paymentdb \
  --clean --if-exists < ~/na-backups/na-paymentdb-<时间戳>.dump
docker compose start na-order-service na-payment-service
```

**异地备份（建议开启）**：
```bash
# WSL 内安装 rclone 并配置 OneDrive（免费 5GB）
curl https://rclone.org/install.sh | sudo bash
rclone config    # 选 OneDrive，按提示授权
# 然后在 .env 里加：
# BACKUP_REMOTE=onedrive:na-backups
```

## 5. 监控与告警

`docker-compose.ops.yml` 提供 Uptime Kuma。启动：

```bash
docker compose -f docker-compose.yml -f docker-compose.ops.yml up -d
```

首次配置（<http://localhost:3001>）：

1. 创建管理员账号；
2. Add New Monitor，逐个添加（类型 HTTP(s)，间隔 60s）：
   - `http://na-order-service:8080/actuator/health`
   - `http://na-payment-service:8080/actuator/health`
   - `http://na-assistant-service:8092/health`
   - `http://na-order-service:8080/login`（页面可用性）
3. Settings → Notifications 配置通知（邮件/企业微信/钉钉 webhook 免费）。

## 6. CI 门禁

`.github/workflows/ci.yml` 在 push 和 PR 时运行：

| Job | 内容 |
|---|---|
| `java` | JDK 8 + `mvn clean verify`（105 个测试） |
| `python` | Python 3.11 + pytest（35 个测试） |
| `image` | 构建 3 个镜像 + Trivy 扫 CRITICAL |
| `secret-scan` | gitleaks 扫历史提交里的密钥 |

本地预检（提交前跑一遍，省一次 CI 往返）：
```bash
bash scripts/preflight.sh
```

**分支保护**：仓库 Settings → Branches → 添加 `master` 规则，勾选 "Require status checks to pass"，选上 CI 的 4 个 job。

## 7. 已知环境限制

| 限制 | 影响 | 处理 |
|---|---|---|
| WSL 不随 Windows 启动 | 重启后服务不会自己起来 | 双击 `scripts\start.cmd` |
| `/mnt/e` 权限恒 777 | 备份/密钥文件无法收紧权限 | 备份放 `~/na-backups`；`.env` 建议移到 `~/.config/na/.env` |
| Windows 对关闭端口静默超时 | `closedPortIsUnreachable` 本机必失败 | CI 上正常；本地可用 `-Dtest=!...` 排除 |
| 无公网 IP | GitHub 无法主动推送部署 | 采用“WSL 主动拉取”模式 |

## 8. 尚未实现（后续可加）

- [ ] staging 环境（独立端口/卷，E2E 只打 staging）
- [ ] 语义化版本自动化（release-please）
- [ ] Grafana + Prometheus 指标面板
- [ ] 非 root / 只读根文件系统（KI-13）
- [ ] 项目迁出 `/mnt/e` 到 `~/demo`（构建速度提升明显）
- [ ] `bootstrap.ps1` 一键重建开发环境
