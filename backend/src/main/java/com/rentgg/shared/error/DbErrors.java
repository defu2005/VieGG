package com.rentgg.shared.error;

import org.springframework.http.HttpStatus;

import java.sql.SQLException;

/** Maps PostgreSQL constraint violations to API errors (§11.3 "Ánh xạ lỗi DB"). */
public final class DbErrors {

    public static final String EXCLUSION_VIOLATION = "23P01";
    public static final String UNIQUE_VIOLATION = "23505";
    /** In the message of the orders trigger that refuses a slot overlapping the owner's busy time (V26). */
    public static final String ACCOUNT_BUSY_BY_OWNER = "account_busy_by_owner";

    private DbErrors() {}

    public static ApiException map(Throwable ex) {
        SQLException sql = findSqlException(ex);
        return sql == null ? null : mapSqlState(sql);
    }

    public static ApiException mapSqlState(SQLException sql) {
        String state = sql.getSQLState();
        String msg = String.valueOf(sql.getMessage());
        if (EXCLUSION_VIOLATION.equals(state) && msg.contains(ACCOUNT_BUSY_BY_OWNER)) {
            return new ApiException(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", "Chủ account đã đánh dấu bận trong khung giờ này.");
        }
        if (EXCLUSION_VIOLATION.equals(state) && msg.contains("ex_orders_no_overlap")) {
            return new ApiException(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", "Khung giờ đã có người đặt cho account này.");
        }
        if (UNIQUE_VIOLATION.equals(state)) {
            if (msg.contains("ux_listings_one_active_per_account")) {
                return ApiException.conflict("LISTING_ALREADY_ACTIVE", "Account này đã có một listing đang hoạt động.");
            }
            if (msg.contains("ux_game_accounts_login") || msg.contains("ux_game_accounts_key")) {
                return ApiException.conflict("GAME_ACCOUNT_DUPLICATE", "Account game này đã được đăng ký trên VieGG.");
            }
            if (msg.contains("ux_dispute_open_per_type")) {
                return ApiException.conflict("DISPUTE_ALREADY_OPEN", "Đã có khiếu nại cùng loại đang mở cho đơn này.");
            }
            if (msg.contains("ux_users_email")) {
                return ApiException.conflict("EMAIL_TAKEN", "Email đã được sử dụng.");
            }
            if (msg.contains("ux_orders_idem") || msg.contains("ux_withdrawals_idem")
                    || msg.contains("ux_payment_intents_idem") || msg.contains("ux_approval_idem")) {
                return ApiException.conflict("IDEMPOTENCY_CONFLICT", "Yêu cầu với Idempotency-Key này đang được xử lý.");
            }
        }
        return null;
    }

    public static boolean isSqlState(Throwable ex, String state, String constraint) {
        SQLException sql = findSqlException(ex);
        return sql != null && state.equals(sql.getSQLState())
                && (constraint == null || String.valueOf(sql.getMessage()).contains(constraint));
    }

    public static SQLException findSqlException(Throwable ex) {
        Throwable t = ex;
        int guard = 0;
        while (t != null && guard++ < 20) {
            if (t instanceof SQLException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }
}
