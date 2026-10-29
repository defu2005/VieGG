package com.rentgg.ledger.domain;

import java.util.UUID;

/**
 * One line of a ledger transaction. Sign convention (§7.1): positive = debit (Nợ), negative = credit (Có).
 */
public record Posting(AccountCode account, UUID ownerUserId, long amount) {

    public Posting {
        if (account.perSeller() && ownerUserId == null) {
            throw new IllegalArgumentException(account + " requires a seller id");
        }
        if (!account.perSeller() && ownerUserId != null) {
            throw new IllegalArgumentException(account + " is a system account");
        }
    }

    public static Posting debit(AccountCode account, long amount) {
        return new Posting(account, null, amount);
    }

    public static Posting credit(AccountCode account, long amount) {
        return new Posting(account, null, -amount);
    }

    public static Posting debit(AccountCode account, UUID seller, long amount) {
        return new Posting(account, seller, amount);
    }

    public static Posting credit(AccountCode account, UUID seller, long amount) {
        return new Posting(account, seller, -amount);
    }
}
