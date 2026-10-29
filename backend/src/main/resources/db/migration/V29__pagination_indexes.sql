-- Indexes behind paged lists, so pages stay fast as data grows.

-- Approvals: queue by status (oldest first) and full history (newest first).
CREATE INDEX IF NOT EXISTS ix_approval_status_created ON approval_requests (status, created_at, id);
CREATE INDEX IF NOT EXISTS ix_approval_created ON approval_requests (created_at DESC, id);

-- Listing review queue by status, oldest update first; owner listings newest first.
CREATE INDEX IF NOT EXISTS ix_listings_status_updated ON listings (status, updated_at, id);
CREATE INDEX IF NOT EXISTS ix_listings_owner_created ON listings (owner_id, created_at DESC, id);

-- Game account verification queue and owner accounts.
CREATE INDEX IF NOT EXISTS ix_game_accounts_state_created ON game_accounts (security_state, created_at, id);
CREATE INDEX IF NOT EXISTS ix_game_accounts_owner_created ON game_accounts (owner_id, created_at DESC, id);

-- Wallet statement: postings of one account, ordered by transaction time.
CREATE INDEX IF NOT EXISTS ix_ledger_tx_created ON ledger_transactions (created_at DESC, id);

-- Withdrawals and helpdesk/disputes lists per user.
CREATE INDEX IF NOT EXISTS ix_withdrawals_owner_created ON withdrawals (owner_id, created_at DESC);
