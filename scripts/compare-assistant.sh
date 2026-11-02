#!/usr/bin/env bash
# 同一输入同时打 Python 版与 Java 版助手服务，对比响应差异（灰度期用）。
#
#   bash scripts/compare-assistant.sh "帮我拿两个机械键盘"
#   bash scripts/compare-assistant.sh            # 用内置样例
#
# 前提：两个助手服务都在运行（docker compose --profile java-assistant up -d）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

log() { printf '\033[36m==>\033[0m %s\n' "$1"; }
ok()  { printf '\033[32m    %s\033[0m\n' "$1"; }
warn(){ printf '\033[33m    %s\033[0m\n' "$1"; }
die() { printf '\033[31m!! %s\033[0m\n' "$1" >&2; exit 1; }

[ -f .env ] || die ".env 不存在"
set -a; . ./.env; set +a

TOKEN="${ASSISTANT_SERVICE_TOKEN:-}"
[ -n "$TOKEN" ] || die "ASSISTANT_SERVICE_TOKEN 为空，两个服务都会拒绝请求"

TEXT="${1:-帮我拿两个机械键盘，再加一张升降桌}"
REQUEST_ID="cmp_$(date +%s)"

# 构造请求体：用一个固定小目录，保证两边输入完全一致
read -r -d '' PAYLOAD <<JSON || true
{
  "requestId": "$REQUEST_ID",
  "userId": 1,
  "text": "$TEXT",
  "excludeProductIds": [],
  "catalog": [
    {"id": 1, "name": "机械键盘 K87", "description": "87 键热插拔机械键盘，白色背光",
     "category": "外设", "tags": "键盘,机械键盘", "price": 299.00, "stock": 25},
    {"id": 6, "name": "人体工学椅", "description": "可调腰托与扶手",
     "category": "家具", "tags": "椅子,工学椅", "price": 1099.00, "stock": 6},
    {"id": 7, "name": "桌面氛围灯", "description": "RGB 氛围灯带",
     "category": "照明", "tags": "灯,氛围灯", "price": 159.00, "stock": 50}
  ]
}
JSON

call() {
    local host="$1" port="$2"
    docker exec demo-na-order-service-1 curl -sS -m 30 \
        -X POST "http://$host:$port/internal/v1/assistant/parse" \
        -H "X-Service-Token: $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$PAYLOAD" 2>/dev/null || echo '{"error":"unreachable"}'
}

log "输入：$TEXT"

PYTHON_RESP="$(call na-assistant-service 8092)"
JAVA_RESP="$(call na-assistant-java 8093)"

echo
echo "--- Python (8092) ---"
echo "$PYTHON_RESP" | python3 -m json.tool 2>/dev/null || echo "$PYTHON_RESP"
echo
echo "--- Java (8093) ---"
echo "$JAVA_RESP" | python3 -m json.tool 2>/dev/null || echo "$JAVA_RESP"

# 归一化后对比：忽略 usage.latencyMs（两边耗时必然不同）
normalize() {
    echo "$1" | python3 -c "
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('INVALID'); raise SystemExit
d.get('usage',{}).pop('latencyMs', None)
# 降级响应只比 status/reason
if d.get('status') == 'DEGRADED':
    print(json.dumps({'status': d.get('status'), 'reason': d.get('reason')}, ensure_ascii=False, sort_keys=True))
else:
    print(json.dumps(d, ensure_ascii=False, sort_keys=True))
" 2>/dev/null || echo "INVALID"
}

P_NORM="$(normalize "$PYTHON_RESP")"
J_NORM="$(normalize "$JAVA_RESP")"

echo
if [ "$P_NORM" = "$J_NORM" ]; then
    ok "两边输出一致"
    exit 0
fi

warn "两边输出存在差异"
echo
echo "--- diff (Python vs Java) ---"
diff <(echo "$P_NORM") <(echo "$J_NORM") || true
echo
echo "提示：若模型本身不确定（temperature=0 仍可能抖动），"
echo "      请用固定 mock 响应做契约测试，而不是依赖本脚本。"
exit 1
