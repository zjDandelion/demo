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
    # 历史格式：<时间戳> <版本>，取最后一行的版本列
    PREVIOUS_TAG="$(tail -n 1 "$HISTORY_FILE" | awk '{print $NF}' || true)"
fi

log "目标版本 $TARGET_TAG（当前 ${PREVIOUS_TAG:-无记录}）"

# 1. 先拉镜像，拉不到就不动线上容器。
#    默认总是拉取，保证拿到仓库里的最新 digest；
#    确实需要离线部署时传 OFFLINE=1 才跳过。
log "拉取镜像"
for svc in $SERVICES; do
    if [ "${OFFLINE:-0}" = "1" ] \
        && docker image inspect "$REGISTRY/$OWNER/$svc:$TARGET_TAG" >/dev/null 2>&1; then
        ok "$svc（离线模式，使用本地镜像）"
        continue
    fi
    docker pull "$REGISTRY/$OWNER/$svc:$TARGET_TAG" \
        || die "拉取 $svc:$TARGET_TAG 失败，线上容器未改动"
    ok "$svc"
done

# 2. 写回 .env（临时文件 + 整体替换，避免 9p 挂载下的缓存问题）
update_env_file() {
    local tmp
    tmp="$(mktemp)"
    cp .env "$tmp"
    local key value
    while [ "$#" -ge 2 ]; do
        key="$1"; value="$2"; shift 2
        if grep -q "^${key}=" "$tmp"; then
            sed -i "s|^${key}=.*|${key}=${value}|" "$tmp"
        else
            printf '%s=%s\n' "$key" "$value" >> "$tmp"
        fi
    done
    cat "$tmp" > .env
    rm -f "$tmp"
}

ENV_PAIRS=(IMAGE_TAG "$TARGET_TAG")
for svc in $SERVICES; do
    ENV_PAIRS+=("$(image_var "$svc")" "$REGISTRY/$OWNER/$svc:$TARGET_TAG")
done
update_env_file "${ENV_PAIRS[@]}"
ok ".env 已更新"

# 3. 重建（--force-recreate 确保镜像变更一定生效）
# /mnt/e 是 9p 挂载，compose 可能读到旧缓存；显式传 --env-file 并等文件可见。
sync
for _ in $(seq 1 10); do
    grep -q "^NA_ORDER_IMAGE=.*:$TARGET_TAG$" .env && break
    sleep 0.5
done
log "重建容器"
docker compose --env-file .env up -d --remove-orphans --force-recreate

# 4. 等健康，并确认容器真的换成了目标镜像
log "等待健康检查（最多 ${HEALTH_TIMEOUT}s）"
EXPECTED="$REGISTRY/$OWNER/na-order-service:$TARGET_TAG"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        # set -euo pipefail 下命令替换的非零退出码会中断脚本，这里显式兜底
        # 按容器名查找，避免 compose ps 在 9p 挂载下返回旧 ID
        CID="$(docker ps -q --filter "name=${COMPOSE_PROJECT:-demo}-na-order-service" 2>/dev/null | head -n1 || true)"
        RUNNING=""
        if [ -n "$CID" ]; then
            RUNNING="$(docker inspect "$CID" --format '{{.Config.Image}}' 2>/dev/null || true)"
        fi
        if [ "$RUNNING" = "$EXPECTED" ]; then
            ok "健康检查通过，镜像已切换"
            printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$TARGET_TAG" >> "$HISTORY_FILE"
            tail -n 10 "$HISTORY_FILE" > "${HISTORY_FILE}.tmp" && mv "${HISTORY_FILE}.tmp" "$HISTORY_FILE"
            log "部署完成：$TARGET_TAG"
            exit 0
        fi
        printf '\033[33m    健康但镜像未切换（%s），继续等待…\033[0m\n' "${RUNNING:-unknown}"
    fi
    sleep 2
done

printf '\033[31m!! 健康检查超时或镜像未切换\033[0m\n' >&2
[ -n "$PREVIOUS_TAG" ] && printf '回滚：bash scripts/rollback.sh\n' >&2
exit 1
