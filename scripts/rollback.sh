#!/usr/bin/env bash
# 回滚到上一个成功部署的版本（WSL 内执行）。
#
#   bash scripts/rollback.sh          # 回滚到历史里上一个版本
#   bash scripts/rollback.sh v1.2.2   # 回滚到指定版本
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

REGISTRY="${REGISTRY:-ghcr.io}"
OWNER="${IMAGE_OWNER:-zjdandelion}"
HISTORY_FILE=".deploy-history"

log() { printf '\033[36m==>\033[0m %s\n' "$1"; }
ok()  { printf '\033[32m    %s\033[0m\n' "$1"; }
die() { printf '\033[31m!! %s\033[0m\n' "$1" >&2; exit 1; }

SERVICES="na-order-service na-payment-service na-assistant-service"
image_var() {
    case "$1" in
        na-order-service)     echo "NA_ORDER_IMAGE" ;;
        na-payment-service)   echo "NA_PAYMENT_IMAGE" ;;
        na-assistant-service) echo "NA_ASSISTANT_IMAGE" ;;
        *) die "未知服务 $1" ;;
    esac
}

[ -f .env ] || die ".env 不存在"
set -a; . ./.env; set +a

CURRENT="${IMAGE_TAG:-}"
TARGET="${1:-}"

if [ -z "$TARGET" ]; then
    [ -f "$HISTORY_FILE" ] || die "没有部署历史，请指定版本：bash scripts/rollback.sh v1.2.2"
    TARGET="$(tail -n 2 "$HISTORY_FILE" | head -n 1 | cut -d' ' -f2 || true)"
    [ -n "$TARGET" ] || die "部署历史不足两条，请指定版本：bash scripts/rollback.sh v1.2.2"
fi

[ "$TARGET" = "$CURRENT" ] && die "当前已是 $TARGET"

log "回滚 $CURRENT -> $TARGET"

for svc in $SERVICES; do
    if ! docker image inspect "$REGISTRY/$OWNER/$svc:$TARGET" >/dev/null 2>&1; then
        docker pull "$REGISTRY/$OWNER/$svc:$TARGET" \
            || die "镜像 $svc:$TARGET 不存在且拉取失败"
    fi
done

set_env() {
    local key="$1" value="$2"
    if grep -q "^${key}=" .env; then
        sed -i "s|^${key}=.*|${key}=${value}|" .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
}
set_env IMAGE_TAG "$TARGET"
for svc in $SERVICES; do
    set_env "$(image_var "$svc")" "$REGISTRY/$OWNER/$svc:$TARGET"
done

docker compose up -d --remove-orphans

deadline=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        ok "已回滚到 $TARGET 并通过健康检查"
        printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$TARGET" >> "$HISTORY_FILE"
        exit 0
    fi
    sleep 2
done

die "回滚后健康检查超时，请检查 docker compose logs"
