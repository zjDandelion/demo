#!/usr/bin/env bash
# S5/S6 崩溃恢复与对账收敛演练。用法（WSL 内，需已部署栈与 .env）：bash tests/drill/m6-s5-s6.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$(dirname "$SCRIPT_DIR")")"
set -a; . ./.env; set +a

BASE=http://127.0.0.1:8081
JAR=/tmp/na-m6-s56-cookies.txt
rm -f "$JAR"

json_value() {
    python3 -c "import json,sys; print(json.loads(sys.stdin.read())['$1'])"
}

order_no() {
    json_value orderNo
}

total_amount() {
    json_value totalAmount
}

payment_no() {
    json_value paymentNo
}

status() {
    json_value status
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

echo "=== S5: STARTED + SUCCEEDED ==="
O5=$(curl -s -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/orders" -d '{"items":[{"productId":4,"quantity":1}]}')
ORDER5=$(echo "$O5" | order_no)
AMT5=$(echo "$O5" | total_amount)
P5=$(internal_pay "$ORDER5" "$AMT5")
PAY5=$(echo "$P5" | payment_no)
echo "S5 order=$ORDER5 payment=$PAY5"

psql_order "INSERT INTO order_payment_attempt (order_no,user_id,amount,status,payment_no,attempt_count,created_at,updated_at) VALUES ('$ORDER5',1,$AMT5,'STARTED','$PAY5',1,now(),now()-interval '60 seconds')"

for i in $(seq 1 10); do
    O=$(curl -s -b "$JAR" "$BASE/api/orders/$ORDER5" | status)
    A=$(psql_order "SELECT status FROM order_payment_attempt WHERE order_no='$ORDER5'")
    echo "S5 t=$((i*4))s order=$O attempt=$A"
    if [ "$O" = PAID ] && [ "$A" = SUCCEEDED ]; then break; fi
    sleep 4
done

echo "=== S6: REFUND_PENDING + SUCCEEDED + cancelled ==="
O6=$(curl -s -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/orders" -d '{"items":[{"productId":4,"quantity":1}]}')
ORDER6=$(echo "$O6" | order_no)
AMT6=$(echo "$O6" | total_amount)
P6=$(internal_pay "$ORDER6" "$AMT6")
PAY6=$(echo "$P6" | payment_no)
echo "S6 order=$ORDER6 payment=$PAY6"

curl -s -b "$JAR" -X POST "$BASE/api/orders/$ORDER6/cancel" >/dev/null
psql_order "INSERT INTO order_payment_attempt (order_no,user_id,amount,status,payment_no,attempt_count,created_at,updated_at) VALUES ('$ORDER6',1,$AMT6,'REFUND_PENDING','$PAY6',1,now(),now()-interval '60 seconds')"

for i in $(seq 1 12); do
    A=$(psql_order "SELECT status FROM order_payment_attempt WHERE order_no='$ORDER6'")
    P=$(psql_payment "SELECT status FROM payments WHERE payment_no='$PAY6'")
    echo "S6 t=$((i*4))s attempt=$A payment=$P"
    if [ "$A" = REFUNDED ] && [ "$P" = REFUNDED ]; then break; fi
    sleep 4
done
