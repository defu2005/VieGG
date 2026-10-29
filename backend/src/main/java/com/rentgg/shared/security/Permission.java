package com.rentgg.shared.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog of every permission the code checks, as {@code resource:action}. Code is the source of truth for
 * which permissions exist; which role holds which permission lives in the database ({@code role_permissions})
 * and can be changed at runtime through the RBAC admin API.
 *
 * <p>Endpoints check them with {@code @PreAuthorize("hasAuthority('listing:approve')")}; services with
 * {@link CurrentUser#has(Permission)}.
 */
public enum Permission {

    // --- self-service (role USER)
    LISTING_MANAGE_OWN("listing:manage_own", "Tạo, sửa, gửi duyệt, tạm dừng listing của mình"),
    GAME_ACCOUNT_MANAGE_OWN("game_account:manage_own", "Quản lý tài khoản game của mình"),
    ORDER_PLACE("order:place", "Tạo đơn thuê và thanh toán"),
    ORDER_MANAGE_OWN("order:manage_own", "Duyệt, từ chối, huỷ, bàn giao đơn mình tham gia"),
    CREDENTIAL_VIEW_OWN("credential:view_own", "Xem thông tin đăng nhập của đơn mình đang thuê"),
    DISPUTE_OPEN("dispute:open", "Mở khiếu nại và bổ sung bằng chứng cho đơn của mình"),
    WALLET_WITHDRAW("wallet:withdraw", "Tạo yêu cầu rút tiền"),

    // --- catalog
    GAME_READ_HIDDEN("game:read_hidden", "Xem game chưa được phép (REVIEW_REQUIRED, PROHIBITED, ...)"),
    GAME_CREATE("game:create", "Tạo game"),
    GAME_UPDATE("game:update", "Sửa chính sách game"),
    GAME_CHANGE_TOS("game:change_tos", "Đổi trạng thái ToS của game"),

    // --- listing moderation
    LISTING_READ_ANY("listing:read_any", "Xem mọi listing kể cả chưa ACTIVE"),
    LISTING_REVIEW_QUEUE("listing:review_queue", "Xem hàng đợi listing theo trạng thái"),
    LISTING_APPROVE("listing:approve", "Duyệt listing"),
    LISTING_REJECT("listing:reject", "Từ chối listing"),
    LISTING_SUSPEND("listing:suspend", "Đình chỉ listing"),

    // --- game accounts
    GAME_ACCOUNT_VERIFY_RECOVERY("game_account:verify_recovery", "Xác minh quyền khôi phục tài khoản game"),
    GAME_ACCOUNT_FLAG_SUSPICIOUS("game_account:flag_suspicious", "Đánh dấu tài khoản game đáng ngờ"),

    // --- users
    USER_READ("user:read", "Xem hồ sơ người dùng"),
    USER_KYC_DECIDE("user:kyc_decide", "Duyệt / từ chối KYC"),
    USER_CHANGE_STATUS("user:change_status", "Đổi trạng thái người dùng"),
    USER_CHANGE_RISK_TIER("user:change_risk_tier", "Đổi mức rủi ro người dùng"),
    USER_READ_ABUSE_SIGNALS("user:read_abuse_signals", "Xem tín hiệu lạm dụng"),

    // --- orders & disputes
    ORDER_READ_ANY("order:read_any", "Xem mọi đơn thuê"),
    DISPUTE_READ_ANY("dispute:read_any", "Xem mọi khiếu nại và hàng đợi khiếu nại"),
    DISPUTE_RESOLVE("dispute:resolve", "Xử lý khiếu nại"),

    // --- help desk chat
    HELPDESK_HANDLE("helpdesk:handle", "Xem, trả lời mọi yêu cầu hỗ trợ và mời người dùng vào cuộc trò chuyện"),

    // --- finance
    WITHDRAWAL_LIST_ALL("withdrawal:list_all", "Xem hàng đợi rút tiền"),
    WITHDRAWAL_READ_BANK_DETAILS("withdrawal:read_bank_details", "Xem đầy đủ thông tin ngân hàng khi rút tiền"),
    WITHDRAWAL_MARK_PAID("withdrawal:mark_paid", "Đánh dấu đã chi"),
    WITHDRAWAL_MARK_FAILED("withdrawal:mark_failed", "Đánh dấu chi thất bại"),
    LEDGER_READ("ledger:read", "Xem sổ cái và đối soát"),

    // --- dual approval (one request + one decide permission per ActionType)
    APPROVAL_LIST("approval:list", "Xem danh sách yêu cầu phê duyệt"),
    APPROVAL_REQUEST_GAME_ALLOW("approval:request:GAME_ALLOW", "Tạo yêu cầu mở lại game"),
    APPROVAL_DECIDE_GAME_ALLOW("approval:decide:GAME_ALLOW", "Duyệt / từ chối yêu cầu mở lại game"),
    APPROVAL_REQUEST_ACCOUNT_SECURITY_RESTORE("approval:request:ACCOUNT_SECURITY_RESTORE",
            "Tạo yêu cầu khôi phục bảo mật tài khoản game"),
    APPROVAL_DECIDE_ACCOUNT_SECURITY_RESTORE("approval:decide:ACCOUNT_SECURITY_RESTORE",
            "Duyệt / từ chối yêu cầu khôi phục bảo mật tài khoản game"),
    APPROVAL_REQUEST_SELLER_HOLD_EARLY_RELEASE("approval:request:SELLER_HOLD_EARLY_RELEASE",
            "Tạo yêu cầu giải ngân sớm cho người bán"),
    APPROVAL_DECIDE_SELLER_HOLD_EARLY_RELEASE("approval:decide:SELLER_HOLD_EARLY_RELEASE",
            "Duyệt / từ chối yêu cầu giải ngân sớm"),
    APPROVAL_REQUEST_LEDGER_ADJUSTMENT("approval:request:LEDGER_ADJUSTMENT", "Tạo yêu cầu điều chỉnh sổ cái"),
    APPROVAL_DECIDE_LEDGER_ADJUSTMENT("approval:decide:LEDGER_ADJUSTMENT", "Duyệt / từ chối yêu cầu điều chỉnh sổ cái"),

    // --- RBAC administration
    ROLE_READ("role:read", "Xem vai trò, quyền và vai trò của người dùng"),
    ROLE_MANAGE("role:manage", "Tạo, sửa, xoá vai trò và gán quyền cho vai trò"),
    ROLE_ASSIGN("role:assign", "Gán / gỡ vai trò cho người dùng"),

    // --- platform settings
    SETTING_READ("setting:read", "Xem cấu hình hệ thống"),
    SETTING_UPDATE("setting:update", "Sửa cấu hình hệ thống (rút tiền, banner, nội dung trang...)"),

    // --- dev tools
    DEV_RUN_JOBS("dev:run_jobs", "Chạy job thủ công (môi trường dev)");

    private static final Map<String, Permission> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;
    private final String description;

    Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }

    /** Customer self-service permission (acting on one's own listings, orders, wallet); not a privilege. */
    public boolean selfService() {
        return switch (this) {
            case LISTING_MANAGE_OWN, GAME_ACCOUNT_MANAGE_OWN, ORDER_PLACE, ORDER_MANAGE_OWN, CREDENTIAL_VIEW_OWN,
                 DISPUTE_OPEN, WALLET_WITHDRAW -> true;
            default -> false;
        };
    }

    public String description() { return description; }

    /** Part before the first ':' (e.g. {@code listing}). */
    public String resource() {
        return code.substring(0, code.indexOf(':'));
    }

    /** Part after the first ':' (e.g. {@code approve}, {@code request:GAME_ALLOW}). */
    public String action() {
        return code.substring(code.indexOf(':') + 1);
    }

    public static Optional<Permission> byCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /** Permission to create an approval request of the given action type ({@code approval:request:<TYPE>}). */
    public static String approvalRequest(String actionType) {
        return "approval:request:" + actionType;
    }

    /** Permission to approve or reject an approval request of the given action type. */
    public static String approvalDecide(String actionType) {
        return "approval:decide:" + actionType;
    }
}
