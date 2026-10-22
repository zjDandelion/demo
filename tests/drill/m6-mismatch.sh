#!/usr/bin/env bash
# 金额不一致支付单的补偿退款演练。用法（WSL 内，需已部署栈与 .env）：bash tests/drill/m6-mismatch.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$(dirname "$SCRIPT_DIR")")"
set -a; . ./.env; set +a

BASE=http://127.0.0.1:8081
JAR=/tmp/na-m6-mismatch-cookies.txt
rm -f "$JAR"

json_value() {
    python3 -c "import json,sys; print(json.loads(sys.stdin.read())['$1'])"
}

login() {
    local csrf
    csrf=$(curl -s -c "$JAR" "$BASE/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
    curl -s -b "$JAR" -c "$JAR" -o /dev/null "$BASE/login" \
        --data-urlencode "username=demo" \
        --data-urlencode "password=123456" \
        --data-urlencode "_csrf=$csrf"
}

internal_pay() {
    local order="$1" amount="$2"
    docker compose exec -T na-payment-service sh -c \
        "curl -s -H 'Content-Type: application/json' -H \"X-Service-Token: \$APP_INTERNAL_TOKEN\" -X POST http://localhost:8080/internal/v1/payments -d '{\"orderNo\":\"$order\",\"userId\":1,\"amount\":$amount,\"currency\":\"CNY\"}'"
}

psql_order() {
    docker compose exec -T na-postgres psql -U na_order_user -d na_orderdb -tAc "$1"
}

psql_payment() {
    docker compose exec -T na-postgres psql -U na_payment_user -d na_paymentdb -tAc "$1"
}

login

echo "=== amount mismatch compensation ==="
BODY=$(curl -s -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/orders" -d '{"items":[{"productId":3,"quantity":1}]}')
ORDER=$(echo "$BODY" | json_value orderNo)
TOTAL=$(echo "$BODY" | json_value totalAmount)
echo "order=$ORDER total=$TOTAL"

P=$(internal_pay "$ORDER" "1.00")
PAY=$(echo "$P" | json_value paymentNo)
STATUS=$(echo "$P" | json_value status)
echo "mismatched payment=$PAY status=$STATUS amount=$(echo "$P" | json_value amount)"

echo "public pay:"
curl -s -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/payments" \
    -d "{\"orderNo\":\"$ORDER\"}" -w '\nHTTP=%{http_code}\n'

for i in $(seq 1 20); do
    A=$(psql_order "SELECT status FROM order_payment_attempt WHERE order_no='$ORDER'")
    P=$(psql_payment "SELECT status FROM payments WHERE payment_no='$PAY'")
    echo "t=$((i*4))s attempt=$A payment=$P"
    if [ "$A" = REFUNDED ] && [ "$P" = REFUNDED ]; then break; fi
    sleep 4
done

curl -s -b "$JAR" "$BASE/api/orders/$ORDER" | json_value status
