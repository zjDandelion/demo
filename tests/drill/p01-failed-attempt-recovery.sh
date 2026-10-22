#!/usr/bin/env bash
# P0-1 线上验证：FAILED 尝试能否被下一次支付复位（只操作一次性订单，结束时取消订单回补库存）
set -uo pipefail
cd /mnt/e/zhaoo_workspace/idea_workspace/demo
BASE=http://127.0.0.1:8081
JAR=/tmp/p01-cookies.txt
rm -f "$JAR"

field() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
msg() { python3 -c "import json,sys; print(json.load(sys.stdin).get('message'))"; }

CSRF=$(curl -s -c "$JAR" "$BASE/login" | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"$//')
curl -s -b "$JAR" -c "$JAR" -o /dev/null -d "username=demo&password=123456&_csrf=$CSRF" "$BASE/login"

ORDER=$(curl -s -b "$JAR" -H 'Content-Type: application/json' \
    -d '{"items":[{"productId":8,"quantity":1}]}' "$BASE/api/orders" | field orderNo)
echo "一次性订单 = $ORDER"

docker compose exec -T na-postgres psql -U na_payment_user -d na_paymentdb -q -c \
    "insert into payments (payment_no, order_no, user_id, amount, currency, channel, status, created_at)
     values ('PAY-P01-'||substr('$ORDER',10), '$ORDER', 1, 1.00, 'CNY', 'MOCK', 'FAILED', now())
     on conflict (order_no) do nothing;"

echo "--- 第 1 次支付：金额/归属不一致 -> attempt 置 FAILED"
curl -s -b "$JAR" -H 'Content-Type: application/json' -d "{\"orderNo\":\"$ORDER\"}" "$BASE/api/payments" | msg
docker compose exec -T na-postgres psql -U na_order_user -d na_orderdb -tAc \
    "select status from order_payment_attempt where order_no='$ORDER'" | tr -d ' ' | sed 's/^/attempt = /'

echo "--- 第 2 次支付：旧代码在这里会返回『支付尝试已失败，请重新下单』"
curl -s -b "$JAR" -H 'Content-Type: application/json' -d "{\"orderNo\":\"$ORDER\"}" "$BASE/api/payments" | msg
docker compose exec -T na-postgres psql -U na_order_user -d na_orderdb -tAc \
    "select attempt_count from order_payment_attempt where order_no='$ORDER'" | tr -d ' ' | sed 's/^/attempt_count = /'

echo "--- 清理：取消订单回补库存"
curl -s -b "$JAR" -X POST -o /dev/null -w "cancel HTTP=%{http_code}\n" "$BASE/api/orders/$ORDER/cancel"
