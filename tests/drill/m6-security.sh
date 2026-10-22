#!/usr/bin/env bash
# 内部 token、越权与跨库权限护栏演练。用法（WSL 内，需已部署栈与 .env）：bash tests/drill/m6-security.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$(dirname "$SCRIPT_DIR")")"
set -a; . ./.env; set +a

BASE=http://127.0.0.1:8081
ORDER=ORD20261022152503792569022
PAY=$(docker compose exec -T na-postgres psql -U na_payment_user -d na_paymentdb -tAc \
    "SELECT payment_no FROM payments WHERE order_no='$ORDER' LIMIT 1")

echo "unauthenticated_api=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/orders")"
echo "unauthenticated_body=$(curl -s "$BASE/api/orders")"
INVALID_TOKEN=$(docker compose exec -T na-order-service curl -s -o /dev/null -w '%{http_code}' \
    -H 'X-Service-Token: wrong-token' "http://na-payment-service:8080/internal/v1/payments/$PAY?userId=1")
echo "invalid_internal_token=$INVALID_TOKEN"

VALID_TOKEN=$(docker compose exec -T na-order-service sh -c \
    "curl -s -o /dev/null -w '%{http_code}' -H \"X-Service-Token: \$APP_PAYMENT_SERVICE_TOKEN\" http://na-payment-service:8080/internal/v1/payments/$PAY?userId=1")
echo "valid_internal_token=$VALID_TOKEN"

echo "cross_db_denied:"
docker compose exec -T na-postgres psql -U na_order_user -d na_paymentdb -tAc 'SELECT 1' 2>&1 | tail -1 || true

docker compose exec -T na-postgres psql -U na_order_user -d na_orderdb -v ON_ERROR_STOP=1 \
    -c "INSERT INTO users (username,password_hash,display_name,created_at)
        SELECT 'other',u.password_hash,'越权测试用户',now()
        FROM users u
        WHERE u.username='demo'
          AND NOT EXISTS (SELECT 1 FROM users x WHERE x.username='other')"

JAR=/tmp/na-m6-other.txt
rm -f "$JAR"
csrf=$(curl -s -c "$JAR" "$BASE/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b "$JAR" -c "$JAR" -o /dev/null "$BASE/login" \
    --data-urlencode "username=other" \
    --data-urlencode "password=123456" \
    --data-urlencode "_csrf=$csrf"
echo "other_order_status=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/orders/$ORDER")"
echo "other_payment_status=$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/payments/$PAY")"
echo "other_order_body=$(curl -s -b "$JAR" "$BASE/api/orders/$ORDER")"
