-- KI-01 / KI-02：引入渠道调用认领时间，用于识别僵死的中间态支付单。
--
-- 中间态语义：
--   PROCESSING  已认领扣款，渠道调用进行中或结果未知
--   REFUNDING   已认领退款
--   CLOSING     已认领关闭
--
-- channel_attempt_at 记录认领时刻；运维可据此挑出长时间停留在中间态的支付单人工介入。
ALTER TABLE payments ADD COLUMN channel_attempt_at TIMESTAMP;

CREATE INDEX idx_payments_status_attempt ON payments (status, channel_attempt_at);
