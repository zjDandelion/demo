#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

mkdir -p backups
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ORDER_DUMP="backups/na-orderdb-${TIMESTAMP}.dump"
PAYMENT_DUMP="backups/na-paymentdb-${TIMESTAMP}.dump"
BACKUP_DONE=0

cleanup() {
    rm -f "${ORDER_DUMP}.tmp" "${PAYMENT_DUMP}.tmp"
    if [ "$BACKUP_DONE" -ne 1 ]; then
        echo "备份未完成，正在恢复启动应用..." >&2
    fi
    docker compose start na-order-service na-payment-service >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose ps --status running --services | grep -qx 'na-postgres' || {
    echo "na-postgres 未运行，请先执行 scripts/deploy.sh"
    exit 1
}

echo "停止应用，保证两个库的一致性..."
docker compose stop na-order-service na-payment-service

echo "导出 na_orderdb..."
docker compose exec -T na-postgres pg_dump -U na_order_user -d na_orderdb -Fc > "${ORDER_DUMP}.tmp"
echo "导出 na_paymentdb..."
docker compose exec -T na-postgres pg_dump -U na_payment_user -d na_paymentdb -Fc > "${PAYMENT_DUMP}.tmp"

for dump in "$ORDER_DUMP" "$PAYMENT_DUMP"; do
    if [ ! -s "${dump}.tmp" ]; then
        echo "备份文件为空：$dump" >&2
        exit 1
    fi
    docker compose exec -T na-postgres pg_restore --list < "${dump}.tmp" > /dev/null
    mv "${dump}.tmp" "$dump"
done

BACKUP_DONE=1
echo "备份完成："
ls -lh "$ORDER_DUMP" "$PAYMENT_DUMP"
