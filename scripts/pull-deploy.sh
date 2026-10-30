#!/usr/bin/env bash
# 从镜像仓库拉取指定版本并重建容器（WSL 内执行）。
#
#   bash scripts/pull-deploy.sh v1.2.3
#   bash scripts/pull-deploy.sh            # 用 .env 里当前 IMAGE_TAG
#
# 行为：
#   1. 先拉镜像，拉不到就不动线上容器
#   2. 写回 .env 并重建
#   3. 等健康检查，成功则记入 .deploy-history
#   4. 失败提示回滚命令
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

REGISTRY="${REGISTRY:-ghcr.io}"
OWNER="${IMAGE_OWNER:-zjdandelion}"
HISTORY_FILE=".deploy-history"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

log() { printf '\033[36m==>\033[0m %s\n' "$1"; }
ok()  { printf '\033[32m    %s\033[0m\n' "$1"; }
die() { printf '\033[31m!! %s\033[0m\n' "$1" >&2; exit 1; }

# 服务名 -> compose 里使用的环境变量
SERVICES="na-order-service na-payment-service na-assistant-service"
image_var() {
    case "$1" in
        na-order-service)     echo "NA_ORDER_IMAGE" ;;
        na-payment-service)   echo "NA_PAYMENT_IMAGE" ;;
        na-assistant-service) echo "NA_ASSISTANT_IMAGE" ;;
        *) die "未知服务 $1" ;;
    esac
}

[ -f .env ] || die ".env 不存在，请先执行 scripts/deploy.sh 或手动创建"
set -a; . ./.env; set +a

TARGET_TAG="${1:-${IMAGE_TAG:-}}"
[ -n "$TARGET_TAG" ] || die "未指定版本：bash scripts/pull-deploy.sh v1.2.3"

PREVIOUS_TAG=""
if [ -f "$HISTORY_FILE" ]; then
    PREVIOUS_TAG="$(tail -n 1 "$HISTORY_FILE" | cut -d' ' -f2 || true)"
fi

log "目标版本 $TARGET_TAG（当前 ${PREVIOUS_TAG:-无记录}）"

# 1. 先拉镜像，拉不到就不动线上容器
log "拉取镜像"
for svc in $SERVICES; do
    docker pull "$REGISTRY/$OWNER/$svc:$TARGET_TAG" \
        || die "拉取 $svc:$TARGET_TAG 失败，线上容器未改动"
    ok "$svc"
done

# 2. 写回 .env
set_env() {
    local key="$1" value="$2"
    if grep -q "^${key}=" .env; then
        sed -i "s|^${key}=.*|${key}=${value}|" .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
}
set_env IMAGE_TAG "$TARGET_TAG"
for svc in $SERVICES; do
    set_env "$(image_var "$svc")" "$REGISTRY/$OWNER/$svc:$TARGET_TAG"
done
ok ".env 已更新"

# 3. 重建
log "重建容器"
docker compose up -d --remove-orphans

# 4. 等健康
log "等待健康检查（最多 ${HEALTH_TIMEOUT}s）"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        ok "健康检查通过"
        printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$TARGET_TAG" >> "$HISTORY_FILE"
        tail -n 10 "$HISTORY_FILE" > "${HISTORY_FILE}.tmp" && mv "${HISTORY_FILE}.tmp" "$HISTORY_FILE"
        log "部署完成：$TARGET_TAG"
        exit 0
    fi
    sleep 2
done

printf '\033[31m!! 健康检查超时\033[0m\n' >&2
[ -n "$PREVIOUS_TAG" ] && printf '回滚：bash scripts/rollback.sh\n' >&2
exit 1
