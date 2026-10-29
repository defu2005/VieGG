package com.rentgg.settings.domain;

/** How a setting value (always stored as text) is validated and edited. */
public enum SettingType {
    /** Whole VND amount. */
    MONEY,
    INTEGER,
    BOOLEAN,
    /** One line, up to {@link #TEXT_MAX} characters. */
    TEXT,
    /** Multi-line, up to {@link #LONG_TEXT_MAX} characters. */
    LONG_TEXT,
    /** Empty, an in-app path ({@code /help}) or an http(s) URL. */
    URL,
    /** One of the setting's choices. */
    CHOICE;

    public static final int TEXT_MAX = 200;
    public static final int LONG_TEXT_MAX = 2000;
}
