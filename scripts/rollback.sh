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

# 以容器实际运行的镜像为准，而不是 .env 里记录的版本
# （.env 可能被上一次中断的部署写脏）
RUNNING_CID="$(docker ps -q --filter "name=${COMPOSE_PROJECT:-demo}-na-order-service" 2>/dev/null | head -n1 || true)"
CURRENT=""
if [ -n "$RUNNING_CID" ]; then
    CURRENT="$(docker inspect "$RUNNING_CID" --format '{{.Config.Image}}' 2>/dev/null | sed 's/.*://' || true)"
fi
[ -n "$CURRENT" ] || CURRENT="${IMAGE_TAG:-}"

TARGET="${1:-}"

if [ -z "$TARGET" ]; then
    [ -f "$HISTORY_FILE" ] || die "没有部署历史，请指定版本：bash scripts/rollback.sh v1.2.2"
    # 取最近一个与当前版本不同的历史版本（跳过重复记录）
    TARGET="$(awk '{print $NF}' "$HISTORY_FILE" | tac | awk -v cur="$CURRENT" '$0 != cur {print; exit}' || true)"
    [ -n "$TARGET" ] || die "找不到可回滚的历史版本，请指定：bash scripts/rollback.sh v1.2.2"
fi

[ "$TARGET" = "$CURRENT" ] && die "当前已是 $TARGET"

log "回滚 $CURRENT -> $TARGET"

for svc in $SERVICES; do
    # 回滚同样要拉取，避免本地残留的旧镜像让回滚指向错误内容
    docker pull "$REGISTRY/$OWNER/$svc:$TARGET" \
        || die "镜像 $svc:$TARGET 拉取失败（如需离线回滚请先手动 docker pull）"
done

# 用临时文件 + mv 整体替换 .env。
# /mnt/e 是 9p 挂载，sed -i 的原地改写可能被 compose 读到旧缓存，
# 整体替换能保证 compose 读到完整的新内容。
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

ENV_PAIRS=(IMAGE_TAG "$TARGET")
for svc in $SERVICES; do
    ENV_PAIRS+=("$(image_var "$svc")" "$REGISTRY/$OWNER/$svc:$TARGET")
done
update_env_file "${ENV_PAIRS[@]}"

# /mnt/e 是 9p 挂载，compose 可能读到旧缓存。
# 显式传 --env-file，并等待文件内容真的可见后再重建。
sync
for _ in $(seq 1 10); do
    grep -q "^NA_ORDER_IMAGE=.*:$TARGET$" .env && break
    sleep 0.5
done

log "重建容器"
docker compose --env-file .env up -d --remove-orphans --force-recreate

deadline=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        # 健康检查通过后再确认容器真的换了镜像。
        # 注意：set -euo pipefail 下，命令替换里的非零退出码会中断脚本，
        # 这里显式兜底，避免把“容器刚启动还没就绪”误判为失败。
        # 按容器名查找，避免 compose ps 在 9p 挂载下返回旧 ID
        CID="$(docker ps -q --filter "name=${COMPOSE_PROJECT:-demo}-na-order-service" 2>/dev/null | head -n1 || true)"
        RUNNING=""
        if [ -n "$CID" ]; then
            RUNNING="$(docker inspect "$CID" --format '{{.Config.Image}}' 2>/dev/null || true)"
        fi
        if [ "$RUNNING" = "$REGISTRY/$OWNER/na-order-service:$TARGET" ]; then
            ok "已回滚到 $TARGET 并通过健康检查"
            printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$TARGET" >> "$HISTORY_FILE"
            exit 0
        fi
        printf '\033[33m    健康但镜像未切换（%s），继续等待…\033[0m\n' "${RUNNING:-unknown}"
    fi
    sleep 2
done

die "回滚后健康检查超时，请检查 docker compose logs"
