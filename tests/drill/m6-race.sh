#!/usr/bin/env bash
# 支付与取消并发竞争演练。用法（WSL 内，需已部署栈与 .env）：bash tests/drill/m6-race.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$(dirname "$SCRIPT_DIR")")"
set -a; . ./.env; set +a

BASE=http://127.0.0.1:8081
JAR=/tmp/na-m6-race-cookies.txt
rm -f "$JAR"

json_value() {
    python3 -c "import json,sys; print(json.loads(sys.stdin.read())['$1'])"
}

psql_order() {
    docker compose exec -T na-postgres psql -U na_order_user -d na_orderdb -tAc "$1"
}

psql_payment() {
    docker compose exec -T na-postgres psql -U na_payment_user -d na_paymentdb -tAc "$1"
}

csrf=$(curl -s -c "$JAR" "$BASE/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b "$JAR" -c "$JAR" -o /dev/null "$BASE/login" \
    --data-urlencode "username=demo" \
    --data-urlencode "password=123456" \
    --data-urlencode "_csrf=$csrf"

STOCK_BEFORE=$(curl -s -b "$JAR" "$BASE/api/products" | python3 -c "import json,sys; print(json.loads(sys.stdin.read())[5]['stock'])")
BODY=$(curl -s -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/orders" -d '{"items":[{"productId":6,"quantity":1}]}')
ORDER=$(echo "$BODY" | json_value orderNo)
echo "order=$ORDER stock_before=$STOCK_BEFORE"

curl -s -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/payments" \
    -d "{\"orderNo\":\"$ORDER\"}" -w '\nHTTP=%{http_code}' -o /tmp/na-m6-race-pay.json > /tmp/na-m6-race-pay.code &
PAY_PID=$!
sleep 0.8
CANCEL=$(curl -s -b "$JAR" -X POST "$BASE/api/orders/$ORDER/cancel" | json_value status)
echo "cancel_status=$CANCEL"
wait "$PAY_PID"
echo "pay_response=$(cat /tmp/na-m6-race-pay.code) $(cat /tmp/na-m6-race-pay.json)"

ORDER_STATUS=$(curl -s -b "$JAR" "$BASE/api/orders/$ORDER" | json_value status)
ATTEMPT=$(psql_order "SELECT status FROM order_payment_attempt WHERE order_no='$ORDER'")
PAYMENT=$(psql_payment "SELECT status FROM payments WHERE order_no='$ORDER'")
STOCK_AFTER=$(curl -s -b "$JAR" "$BASE/api/products" | python3 -c "import json,sys; print(json.loads(sys.stdin.read())[5]['stock'])")
echo "final order=$ORDER_STATUS attempt=$ATTEMPT payment=$PAYMENT stock_after=$STOCK_AFTER"
