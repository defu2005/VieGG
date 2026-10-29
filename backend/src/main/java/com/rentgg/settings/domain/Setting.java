package com.rentgg.settings.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog of every runtime setting. Code decides which settings exist, their type, default and limits; the
 * {@code app_settings} table only holds the values an admin changed. {@code publicValue} settings are served to
 * everyone (web, mobile) through {@code GET /api/v1/settings/public}; the others are staff only.
 */
public enum Setting {

    // --- withdrawals
    WITHDRAWAL_ENABLED("withdrawal.enabled", Group.WITHDRAWAL, SettingType.BOOLEAN, "true", true,
            "Cho phép rút tiền", "Tắt để tạm ngưng nhận yêu cầu rút tiền mới (yêu cầu đã tạo vẫn được xử lý)."),
    WITHDRAWAL_MIN_AMOUNT("withdrawal.min_amount", Group.WITHDRAWAL, SettingType.MONEY, "10000", true,
            "Số tiền rút tối thiểu (đ)", "Mỗi yêu cầu rút phải từ số tiền này trở lên.", 1, 1_000_000_000L),
    WITHDRAWAL_MAX_AMOUNT("withdrawal.max_amount", Group.WITHDRAWAL, SettingType.MONEY, "0", true,
            "Số tiền rút tối đa mỗi lần (đ)", "0 = không giới hạn (chỉ bị giới hạn bởi số dư).", 0, 10_000_000_000L),
    WITHDRAWAL_NOTICE("withdrawal.notice", Group.WITHDRAWAL, SettingType.LONG_TEXT,
            "Yêu cầu rút tiền được xử lý trong 1–2 ngày làm việc.", true,
            "Ghi chú ở form rút tiền", "Hiện cho người dùng ngay dưới form rút tiền. Để trống để ẩn."),

    // --- site texts
    SITE_NAME("site.name", Group.SITE, SettingType.TEXT, "VieGG", true,
            "Tên trang", "Dùng ở chân trang và tiêu đề tab trình duyệt."),
    SITE_TITLE("site.title", Group.SITE, SettingType.TEXT, "VieGG — Thuê account game theo giờ", true,
            "Tiêu đề tab trình duyệt", "Tiêu đề trang chủ trên tab trình duyệt / kết quả tìm kiếm."),
    SITE_FOOTER_TEXT("site.footer_text", Group.SITE, SettingType.TEXT,
            "Thuê account game theo giờ, an toàn và minh bạch.", true,
            "Dòng giới thiệu ở chân trang", null),
    SUPPORT_EMAIL("support.email", Group.SITE, SettingType.TEXT, "", true,
            "Email hỗ trợ", "Hiện ở chân trang. Để trống để ẩn."),
    SUPPORT_HOTLINE("support.hotline", Group.SITE, SettingType.TEXT, "", true,
            "Hotline hỗ trợ", "Hiện ở chân trang. Để trống để ẩn."),

    // --- home page hero
    HOME_HERO_EYEBROW("home.hero_eyebrow", Group.HOME, SettingType.TEXT, "🎮 Chợ thuê account game theo giờ", true,
            "Dòng nhỏ trên tiêu đề", null),
    HOME_HERO_TITLE("home.hero_title", Group.HOME, SettingType.TEXT,
            "Thuê account game theo giờ — nhận ngay, chơi an toàn", true, "Tiêu đề lớn trang chủ", null),
    HOME_HERO_SUBTITLE("home.hero_subtitle", Group.HOME, SettingType.LONG_TEXT,
            "Kết nối người cần chơi với chủ account đã được kiểm duyệt. Tiền thuê và cọc được giữ theo quy định, "
                    + "hoàn tất trong vài phút.", true, "Mô tả dưới tiêu đề", null),

    // --- announcement banner
    BANNER_ENABLED("banner.enabled", Group.BANNER, SettingType.BOOLEAN, "false", true,
            "Hiện banner", "Banner thông báo ở đầu mọi trang."),
    BANNER_MESSAGE("banner.message", Group.BANNER, SettingType.LONG_TEXT, "", true,
            "Nội dung banner", "Bắt buộc khi bật banner."),
    BANNER_VARIANT("banner.variant", Group.BANNER, SettingType.CHOICE, "info", true,
            "Kiểu banner", null, List.of("info", "success", "warning", "danger")),
    BANNER_LINK_URL("banner.link_url", Group.BANNER, SettingType.URL, "", true,
            "Liên kết (tuỳ chọn)", "Đường dẫn trong trang (bắt đầu bằng /) hoặc https://..."),
    BANNER_LINK_LABEL("banner.link_label", Group.BANNER, SettingType.TEXT, "Xem chi tiết", true,
            "Chữ trên liên kết", null),
    BANNER_DISMISSIBLE("banner.dismissible", Group.BANNER, SettingType.BOOLEAN, "true", true,
            "Cho phép tắt banner", "Người xem bấm ✕ để ẩn; banner hiện lại khi nội dung thay đổi.");

    public enum Group {
        WITHDRAWAL("Rút tiền"), SITE("Thông tin trang"), HOME("Trang chủ"), BANNER("Banner thông báo");

        private final String label;

        Group(String label) { this.label = label; }

        public String label() { return label; }
    }

    private static final Map<String, Setting> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Setting::key, Function.identity()));

    private final String key;
    private final Group group;
    private final SettingType type;
    private final String defaultValue;
    private final boolean publicValue;
    private final String label;
    private final String description;
    private final Long min;
    private final Long max;
    private final List<String> choices;

    Setting(String key, Group group, SettingType type, String defaultValue, boolean publicValue, String label,
            String description) {
        this(key, group, type, defaultValue, publicValue, label, description, null, null, List.of());
    }

    Setting(String key, Group group, SettingType type, String defaultValue, boolean publicValue, String label,
            String description, long min, long max) {
        this(key, group, type, defaultValue, publicValue, label, description, min, max, List.of());
    }

    Setting(String key, Group group, SettingType type, String defaultValue, boolean publicValue, String label,
            String description, List<String> choices) {
        this(key, group, type, defaultValue, publicValue, label, description, null, null, choices);
    }

    Setting(String key, Group group, SettingType type, String defaultValue, boolean publicValue, String label,
            String description, Long min, Long max, List<String> choices) {
        this.key = key;
        this.group = group;
        this.type = type;
        this.defaultValue = defaultValue;
        this.publicValue = publicValue;
        this.label = label;
        this.description = description;
        this.min = min;
        this.max = max;
        this.choices = choices;
    }

    public String key() { return key; }
    public Group group() { return group; }
    public SettingType type() { return type; }
    public String defaultValue() { return defaultValue; }
    public boolean publicValue() { return publicValue; }
    public String label() { return label; }
    public String description() { return description; }
    public Long min() { return min; }
    public Long max() { return max; }
    public List<String> choices() { return choices; }

    public static Optional<Setting> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
