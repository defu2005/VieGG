package com.rentgg.dispute.domain;

public enum DisputeType {
    OWNER_DAMAGE, ACCOUNT_TAKEOVER, RENTER_LOGIN_FAILED, RENTER_NOT_AS_DESCRIBED,
    /** About one OWNER risk of the order snapshot (needs riskId): deduction capped by that risk's deposit. */
    OWNER_RISK,
    /** About one RENTER risk of the order snapshot (needs riskId): rent refund + the owner's compensation. */
    RENTER_RISK;

    public boolean byRenter() {
        return this == RENTER_LOGIN_FAILED || this == RENTER_NOT_AS_DESCRIBED || this == RENTER_RISK;
    }

    public boolean riskBased() {
        return this == OWNER_RISK || this == RENTER_RISK;
    }

    /** Owner disputes target the deposit; renter disputes target the rent. */
    public boolean targetsDeposit() {
        return !byRenter();
    }
}
