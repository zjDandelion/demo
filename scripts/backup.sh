#!/usr/bin/env bash
# 一致性备份：停应用 -> pg_dump 双库 -> 校验 -> 恢复应用 -> 清理旧备份 -> 可选异地同步。
#
#   bash scripts/backup.sh
#
# 可选环境变量（写在 .env 里即可生效）：
#   BACKUP_KEEP_DAILY   保留最近多少个每日备份，默认 7
#   BACKUP_REMOTE       异地目标，例如 onedrive:na-backups（需先 rclone config）
#
# 权限：备份目录与文件统一 700/600，避免同机其他账号读到业务数据。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

umask 077

# /mnt/e 是 9p 挂载，chmod 不生效（权限恒为 777），备份默认放到 WSL 原生文件系统。
# 需要放回项目目录时显式指定 BACKUP_DIR。
BACKUP_DIR="${BACKUP_DIR:-$HOME/na-backups}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

if [ -f .env ]; then
    set -a; . ./.env; set +a
fi

BACKUP_KEEP_DAILY="${BACKUP_KEEP_DAILY:-7}"
BACKUP_REMOTE="${BACKUP_REMOTE:-}"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ORDER_DUMP="${BACKUP_DIR}/na-orderdb-${TIMESTAMP}.dump"
PAYMENT_DUMP="${BACKUP_DIR}/na-paymentdb-${TIMESTAMP}.dump"
BACKUP_DONE=0
APP_STOPPED=0

log() { printf '\033[36m==>\033[0m %s\n' "$1"; }
ok()  { printf '\033[32m    %s\033[0m\n' "$1"; }
die() { printf '\033[31m!! %s\033[0m\n' "$1" >&2; exit 1; }

cleanup() {
    rm -f "${ORDER_DUMP}.tmp" "${PAYMENT_DUMP}.tmp"
    if [ "$APP_STOPPED" -eq 1 ]; then
        if [ "$BACKUP_DONE" -eq 1 ]; then
            log "恢复启动应用"
        else
            printf '\033[33m备份未完成，正在恢复启动应用\033[0m\n' >&2
        fi
        docker compose start na-order-service na-payment-service >/dev/null 2>&1 || true
    fi
}

# 预检放在 trap 之前，避免 postgres 没起时误把应用拉起来（KI-07）
docker compose ps --status running --services 2>/dev/null | grep -qx 'na-postgres' || {
    die "na-postgres 未运行，请先执行 scripts/start.cmd 或 scripts/deploy.sh"
}

trap cleanup EXIT

log "停止应用，保证两个库的一致性"
docker compose stop na-order-service na-payment-service
APP_STOPPED=1

log "导出 na_orderdb"
docker compose exec -T na-postgres pg_dump -U na_order_user -d na_orderdb -Fc > "${ORDER_DUMP}.tmp"

log "导出 na_paymentdb"
docker compose exec -T na-postgres pg_dump -U na_payment_user -d na_paymentdb -Fc > "${PAYMENT_DUMP}.tmp"

for dump in "$ORDER_DUMP" "$PAYMENT_DUMP"; do
    [ -s "${dump}.tmp" ] || die "备份文件为空：$dump"
    docker compose exec -T na-postgres pg_restore --list < "${dump}.tmp" > /dev/null \
        || die "备份文件校验失败：$dump"
    mv "${dump}.tmp" "$dump"
    chmod 600 "$dump"
done

BACKUP_DONE=1
ok "备份完成"

# 清理超出保留数量的旧备份
log "清理旧备份（保留最近 ${BACKUP_KEEP_DAILY} 组）"
for prefix in na-orderdb na-paymentdb; do
    while IFS= read -r file; do
        [ -n "$file" ] || continue
        rm -f "$file"
        ok "删除 $file"
    done < <(ls -1t "${BACKUP_DIR}/${prefix}-"*.dump 2>/dev/null | tail -n "+$((BACKUP_KEEP_DAILY + 1))" || true)
done

# 可选：异地同步
if [ -n "$BACKUP_REMOTE" ]; then
    if command -v rclone >/dev/null 2>&1; then
        log "同步到 $BACKUP_REMOTE"
        if rclone copy "$BACKUP_DIR" "$BACKUP_REMOTE" --include "*.dump" --max-age 24h -q; then
            ok "异地同步完成"
        else
            printf '\033[33m异地同步失败（本地备份仍可用）\033[0m\n' >&2
        fi
    else
        printf '\033[33m未安装 rclone，跳过异地同步\033[0m\n' >&2
    fi
else
    printf '\033[33m未配置 BACKUP_REMOTE，备份仅在本机\033[0m\n' >&2
fi

echo
ls -lh "$ORDER_DUMP" "$PAYMENT_DUMP"
