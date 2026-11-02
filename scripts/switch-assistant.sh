#!/usr/bin/env bash
# 在 Python 版与 Java 版助手服务之间切换（WSL 内执行）。
#
#   bash scripts/switch-assistant.sh java     # 切到 Java 版
#   bash scripts/switch-assistant.sh python   # 切回 Python 版
#   bash scripts/switch-assistant.sh status   # 查看当前指向
#
# 切换后只重建订单服务，助手服务本身不动（两个都保留运行）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

PYTHON_URL="http://na-assistant-service:8092"
JAVA_URL="http://na-assistant-java:8093"

log() { printf '\033[36m==>\033[0m %s\n' "$1"; }
ok()  { printf '\033[32m    %s\033[0m\n' "$1"; }
die() { printf '\033[31m!! %s\033[0m\n' "$1" >&2; exit 1; }

[ -f .env ] || die ".env 不存在"

current_url() {
    grep -E '^APP_ASSISTANT_URL=' .env 2>/dev/null | cut -d= -f2- || true
}

case "${1:-status}" in
    python|java)
        if [ "$1" = "java" ]; then
            TARGET_URL="$JAVA_URL"
            PROFILE="java-assistant"
        else
            TARGET_URL="$PYTHON_URL"
            PROFILE=""
        fi

        log "切换助手服务到 $1（$TARGET_URL）"

        # Java 版需要先确保容器在跑
        if [ "$1" = "java" ]; then
            if ! docker ps --format '{{.Names}}' | grep -qx 'demo-na-assistant-java-1'; then
                log "启动 Java 版助手服务"
                docker compose --profile java-assistant up -d na-assistant-java
            fi
            for i in $(seq 1 30); do
                if docker exec demo-na-assistant-java-1 curl -fsS -m 3 http://localhost:8093/health >/dev/null 2>&1; then
                    ok "Java 版已就绪"
                    break
                fi
                [ "$i" -eq 30 ] && die "Java 版在 90 秒内未就绪，请检查 docker compose logs na-assistant-java"
                sleep 3
            done
        fi

        # 写回 .env（临时文件整体替换，避免 9p 缓存问题）
        tmp="$(mktemp)"
        cp .env "$tmp"
        if grep -q '^APP_ASSISTANT_URL=' "$tmp"; then
            sed -i "s|^APP_ASSISTANT_URL=.*|APP_ASSISTANT_URL=$TARGET_URL|" "$tmp"
        else
            printf 'APP_ASSISTANT_URL=%s\n' "$TARGET_URL" >> "$tmp"
        fi
        if grep -q '^APP_ASSISTANT_ENABLED=' "$tmp"; then
            sed -i "s|^APP_ASSISTANT_ENABLED=.*|APP_ASSISTANT_ENABLED=true|" "$tmp"
        else
            printf 'APP_ASSISTANT_ENABLED=true\n' >> "$tmp"
        fi
        cat "$tmp" > .env
        rm -f "$tmp"
        sync
        ok ".env 已更新"

        # 重新加载，否则 shell 里的旧值会覆盖 compose 对 .env 的读取
        set -a; . ./.env; set +a

        log "重建订单服务"
        docker compose up -d --force-recreate na-order-service

        for i in $(seq 1 40); do
            if curl -fsS -m 3 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
                ok "订单服务已就绪，当前指向 $1"
                exit 0
            fi
            sleep 3
        done
        die "订单服务在 120 秒内未就绪"
        ;;
    status)
        URL="$(current_url)"
        ENABLED="$(grep -E '^APP_ASSISTANT_ENABLED=' .env 2>/dev/null | cut -d= -f2- || echo 'false')"
        echo "APP_ASSISTANT_ENABLED = ${ENABLED:-false}"
        echo "APP_ASSISTANT_URL     = ${URL:-（未设置，使用 compose 默认值 $PYTHON_URL）}"
        case "$URL" in
            *8093*) echo "当前使用：Java 版" ;;
            *8092*|"") echo "当前使用：Python 版" ;;
            *) echo "当前使用：自定义地址" ;;
        esac
        ;;
    *)
        die "用法：bash scripts/switch-assistant.sh [java|python|status]"
        ;;
esac
