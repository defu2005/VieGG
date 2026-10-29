package com.rentgg.app.ui

import androidx.compose.ui.graphics.Color
import com.rentgg.app.data.ApiException
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val vi = Locale("vi", "VN")
private val dateFmt = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy", vi).withZone(ZoneId.systemDefault())

fun vnd(amount: Long): String = NumberFormat.getIntegerInstance(vi).format(amount) + "đ"

fun fmtTime(iso: String?): String =
    iso?.let { runCatching { dateFmt.format(Instant.parse(it)) }.getOrNull() } ?: "—"

fun errorText(e: Throwable): String = when (e) {
    is ApiException -> e.message ?: "Có lỗi xảy ra."
    else -> "Có lỗi xảy ra. Vui lòng thử lại."
}

fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m phút"
        m == 0 -> "$h giờ"
        else -> "$h giờ $m phút"
    }
}

data class StatusStyle(val label: String, val color: Color)

fun orderStatus(s: String): StatusStyle = when (s) {
    "PENDING_PAYMENT" -> StatusStyle("Chờ thanh toán", Orange)
    "AWAITING_OWNER_APPROVAL" -> StatusStyle("Chờ chủ duyệt", Blue)
    "CONFIRMED" -> StatusStyle("Đã xác nhận", Blue)
    "IN_RENTAL" -> StatusStyle("Đang thuê", Green)
    "ENDED" -> StatusStyle("Hoàn thành", Muted)
    "CANCELLED" -> StatusStyle("Đã hủy", Muted)
    "EXPIRED" -> StatusStyle("Hết hạn", Muted)
    "REJECTED" -> StatusStyle("Bị từ chối", Muted)
    else -> StatusStyle(s, Muted)
}

fun listingStatus(s: String): StatusStyle = when (s) {
    "DRAFT" -> StatusStyle("Nháp", Muted)
    "PENDING_REVIEW" -> StatusStyle("Chờ duyệt", Blue)
    "ACTIVE" -> StatusStyle("Đang cho thuê", Green)
    "PAUSED" -> StatusStyle("Tạm dừng", Orange)
    "REJECTED" -> StatusStyle("Bị từ chối", Muted)
    "SUSPENDED" -> StatusStyle("Bị đình chỉ", Muted)
    else -> StatusStyle(s, Muted)
}

fun endModeText(m: String) = when (m) {
    "FORCE_AT_END" -> "Ngắt đúng giờ"
    "GRACE_FOR_CURRENT_MATCH" -> "Cho phép hết trận đang chơi"
    "STAY_ONLINE_UNTIL_RECLAIM" -> "Giữ online đến khi chủ lấy lại"
    else -> m
}

fun disputeTypeText(t: String) = when (t) {
    "OWNER_DAMAGE" -> "Chủ: account bị hư hại"
    "ACCOUNT_TAKEOVER" -> "Chủ: bị chiếm account"
    "RENTER_LOGIN_FAILED" -> "Không đăng nhập được"
    "RENTER_NOT_AS_DESCRIBED" -> "Account không như mô tả"
    else -> t
}
