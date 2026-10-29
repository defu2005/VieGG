-- Every user has one wallet (ledger account SELLER_AVAILABLE of that user): top-ups come in through the PSP,
-- orders and extensions are paid from it, refunds and seller revenue go back to it, withdrawals take from it.

-- A top-up is a payment intent without an order.
ALTER TABLE payment_intents ALTER COLUMN order_id DROP NOT NULL;
CREATE UNIQUE INDEX ux_payment_intents_topup_idem ON payment_intents (payer_id, idempotency_key) WHERE order_id IS NULL;
CREATE INDEX ix_payment_intents_payer ON payment_intents (payer_id, created_at);

ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_type_check CHECK (type IN (
    'PAYMENT_CAPTURED','ORDER_ENDED','SELLER_HOLD_RELEASED','SELLER_DISPUTE_HOLD','SELLER_DISPUTE_HOLD_RELEASED',
    'DEPOSIT_REFUND_REQUESTED','REFUND_COMPLETED','DEPOSIT_DEDUCTED','RENT_REFUND','ORDER_CANCELLED_REFUND',
    'ORDER_CANCELLED_REVENUE','WITHDRAWAL_REQUESTED','WITHDRAWAL_PAID','WITHDRAWAL_FAILED','LEDGER_ADJUSTMENT',
    'WALLET_TOPUP','WALLET_PAYMENT'));
