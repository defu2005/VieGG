package com.rentgg.catalog.domain;

/** What identifies one game account inside a game, so the same account cannot be added twice. */
public enum AccountKeyType {
    /** The login username (HMAC fingerprint only, never stored in clear). */
    LOGIN,
    /** A UUID such as a Minecraft player UUID. */
    UUID,
    /** A numeric UID such as a Genshin / Free Fire UID. */
    NUMERIC_ID,
    /** An in-game name or tag such as a Riot ID "Name#TAG". */
    NAME
}
