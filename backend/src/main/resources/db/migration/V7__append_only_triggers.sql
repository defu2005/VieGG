-- Append-only: audit_logs, ledger_transactions, ledger_postings
CREATE OR REPLACE FUNCTION forbid_update_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only (% not allowed)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_ledger_tx_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_ledger_tx_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_ledger_postings_append_only
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_ledger_postings_no_truncate
    BEFORE TRUNCATE ON ledger_postings
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();

-- Every ledger transaction must balance (sum of postings = 0) at COMMIT.
CREATE OR REPLACE FUNCTION check_ledger_tx_balanced() RETURNS trigger AS $$
DECLARE
    total bigint;
    cnt   int;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*) INTO total, cnt
      FROM ledger_postings WHERE transaction_id = NEW.transaction_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % is unbalanced: sum = %', NEW.transaction_id, total
            USING ERRCODE = 'check_violation';
    END IF;
    IF cnt < 2 THEN
        RAISE EXCEPTION 'Ledger transaction % needs at least 2 postings', NEW.transaction_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balanced
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_tx_balanced();
