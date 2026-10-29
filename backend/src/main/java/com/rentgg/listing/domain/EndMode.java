package com.rentgg.listing.domain;

/** How a rental ends (UC-37). Password rotation is the only real session-ending mechanism. */
public enum EndMode {
    FORCE_AT_END,
    GRACE_FOR_CURRENT_MATCH,
    STAY_ONLINE_UNTIL_RECLAIM;

    /**
     * Minutes the account stays blocked after scheduled_end_at (block_end_at = scheduled_end_at + this).
     * Only FORCE_AT_END takes the account back right at the end; the other modes leave the renter in game for a
     * while, so that time must not be bookable by anyone else.
     */
    public int bufferMinutes(int graceMinutes) {
        return this == FORCE_AT_END ? 0 : graceMinutes;
    }
}
