#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

docker info >/dev/null 2>&1 || {
    echo "Docker 未运行，请先启动 WSL/Docker"
    exit 1
}

docker compose version >/dev/null 2>&1 || {
    echo "缺少 docker compose 插件"
    exit 1
}

generate_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 16
    else
        od -An -N16 -tx1 /dev/urandom | tr -d ' \n'
    fi
}

if [ ! -f .env ]; then
    umask 077
    cat > .env <<EOF
POSTGRES_PASSWORD=$(generate_secret)
NA_ORDER_DB_PASSWORD=$(generate_secret)
NA_PAYMENT_DB_PASSWORD=$(generate_secret)
INTERNAL_SERVICE_TOKEN=$(generate_secret)
EOF
    echo "已生成 .env（数据库密码与内部 token）"
fi

set -a
# shellcheck disable=SC1091
. ./.env
set +a

for name in POSTGRES_PASSWORD NA_ORDER_DB_PASSWORD NA_PAYMENT_DB_PASSWORD INTERNAL_SERVICE_TOKEN; do
    if [ -z "${!name:-}" ]; then
        echo ".env 缺少 $name"
        exit 1
    fi
done

if ! docker compose ps --status running --services | grep -qx 'na-order-service'; then
    if command -v ss >/dev/null 2>&1 && ss -ltn | grep -qE '[:.]8081[[:space:]]'; then
        if docker ps --format '{{.Names}}' | grep -qx 'demo-order-app-1'; then
            echo "宿主机 8081 仍被旧单体容器 demo-order-app-1 占用。"
            echo "请先执行：docker stop demo-order-app-1（保留旧镜像与旧卷）"
        else
            echo "宿主机 8081 已被其他进程占用，请释放后重试。"
        fi
        exit 1
    fi
fi

docker compose config -q
docker compose up -d --build --wait --wait-timeout 600
docker compose ps
echo "部署完成：http://localhost:8081"
echo "备份命令：bash scripts/backup.sh"
