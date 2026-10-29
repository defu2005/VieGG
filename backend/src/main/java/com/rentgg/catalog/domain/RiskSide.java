package com.rentgg.catalog.domain;

/** Who suffers the damage when a risk happens. */
public enum RiskSide {
    /** The renter breaks the account (lost items, changed recovery info, ban…): covered by the renter's deposit. */
    OWNER,
    /** The renter cannot use what they paid for (wrong password, not as described…): rent refund + optional compensation. */
    RENTER
}
