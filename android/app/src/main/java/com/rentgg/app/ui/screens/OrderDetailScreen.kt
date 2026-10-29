package com.rentgg.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rentgg.app.BuildConfig
import com.rentgg.app.data.Credentials
import com.rentgg.app.data.Dispute
import com.rentgg.app.data.GameView
import com.rentgg.app.data.Me
import com.rentgg.app.data.OpenDisputeRequest
import com.rentgg.app.data.OrderDetail
import com.rentgg.app.data.OtpNoteRequest
import com.rentgg.app.data.PayResponse
import com.rentgg.app.ui.LoadView
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.LocalSnack
import com.rentgg.app.ui.Muted
import com.rentgg.app.ui.Orange
import com.rentgg.app.ui.OrangeTopBar
import com.rentgg.app.ui.PriceRed
import com.rentgg.app.ui.PrimaryButton
import com.rentgg.app.ui.StatusChip
import com.rentgg.app.ui.disputeTypeText
import com.rentgg.app.ui.errorText
import com.rentgg.app.ui.fmtTime
import com.rentgg.app.ui.orderStatus
import com.rentgg.app.ui.rememberLoader
import com.rentgg.app.ui.vnd
import kotlinx.coroutines.launch

@Composable
fun OrderDetailScreen(id: String, nav: NavHostController) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val me = rememberLoader { api.get<Me>("me") }
    val detail = rememberLoader(id) { api.get<OrderDetail>("orders/$id") }
    val disputes = rememberLoader(id) { runCatching { api.get<List<Dispute>>("orders/$id/disputes") }.getOrDefault(emptyList()) }

    var busy by remember { mutableStateOf(false) }
    var pay by remember { mutableStateOf<PayResponse?>(null) }
    var credentials by remember { mutableStateOf<Credentials?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var openDispute by remember { mutableStateOf(false) }
    var otpNote by remember { mutableStateOf("") }

    // Khi đang hiện thông tin đăng nhập: chặn chụp màn hình / xem trước ở màn hình đa nhiệm.
    DisposableEffect(credentials != null) {
        val window = (context as? Activity)?.window
        if (credentials != null) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun act(ok: String?, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                ok?.let(snack)
                detail.reload()
                disputes.reload()
            } catch (e: Exception) {
                snack(errorText(e))
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = { OrangeTopBar("Chi tiết đơn", onBack = { nav.popBackStack() }) }, containerColor = com.rentgg.app.ui.Surface0) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LoadView(detail) { d ->
                val o = d.order
                val myId = (me.state as? com.rentgg.app.ui.Load.Ready)?.data?.id
                val isRenter = myId == o.renterId
                val isOwner = myId == o.ownerId
                val game = rememberLoader(o.gameId) { runCatching { api.get<GameView>("games/${o.gameId}") }.getOrNull() }
                val otpMode = (game.state as? com.rentgg.app.ui.Load.Ready)?.data?.handoverMode == "OWNER_ASSISTED_OTP"
                val live = o.status == "CONFIRMED" || o.status == "IN_RENTAL"

                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (isOwner) "Đơn cho thuê" else "Đơn thuê", fontWeight = FontWeight.Bold)
                                StatusChip(orderStatus(o.status))
                            }
                            Line("Bắt đầu", fmtTime(o.startAt))
                            Line("Kết thúc", fmtTime(o.scheduledEndAt))
                            Line("Tiền thuê", vnd(o.rentAmount))
                            Line("Tiền cọc", vnd(o.depositAmount))
                            if (isOwner) {
                                Line("Phí nền tảng", vnd(o.platformFee))
                                Line("Thực nhận", vnd(o.sellerNet))
                            } else {
                                Line("Cần thanh toán", vnd(o.amountToPay), highlight = true)
                            }
                            d.deposit?.let { Line("Cọc (${it.status})", vnd(it.amount)) }
                            if (o.status == "PENDING_PAYMENT") Line("Hạn thanh toán", fmtTime(o.paymentExpiresAt))
                            if (o.status == "AWAITING_OWNER_APPROVAL") Line("Hạn chủ duyệt", fmtTime(o.approvalExpiresAt))
                            o.cancelReason?.let { Line("Lý do hủy", it) }
                        }
                    }

                    // ---- Hành động theo vai trò và trạng thái ----
                    if (isRenter && o.status == "PENDING_PAYMENT") {
                        if (pay == null) {
                            PrimaryButton("Thanh toán ${vnd(o.amountToPay)}", loading = busy) {
                                act("Đã tạo giao dịch thanh toán.") { pay = api.post<PayResponse>("orders/$id/pay", idempotent = true) }
                            }
                        } else {
                            val p = pay!!
                            Card(colors = CardDefaults.cardColors(containerColor = com.rentgg.app.ui.OrangeSoft)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Giao dịch ${vnd(p.amount)} đang chờ thanh toán.")
                                    p.checkoutUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                                        PrimaryButton("Mở trang thanh toán") {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    }
                                    if (BuildConfig.DEV_TOOLS) {
                                        OutlinedButton({
                                            act("Đã giả lập thanh toán thành công (dev).") {
                                                api.postUnit("dev/payments/${p.intentId}/simulate-success")
                                                pay = null
                                            }
                                        }, Modifier.fillMaxWidth()) { Text("Giả lập thanh toán (dev)") }
                                    }
                                }
                            }
                        }
                    }
                    if (isOwner && o.status == "AWAITING_OWNER_APPROVAL") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Duyệt đơn", loading = busy, modifier = Modifier.weight(1f)) {
                                act("Đã duyệt đơn.") { api.postUnit("orders/$id/approve") }
                            }
                            OutlinedButton({ act("Đã từ chối đơn.") { api.postUnit("orders/$id/reject") } }, Modifier.weight(1f)) { Text("Từ chối") }
                        }
                    }
                    if (isRenter && live) {
                        PrimaryButton("Xem tài khoản đăng nhập", loading = busy) {
                            act(null) { credentials = api.get<Credentials>("orders/$id/credentials"); showPassword = false }
                        }
                        if (otpMode) {
                            OutlinedButton({ act("Đã gửi yêu cầu OTP cho chủ account.") { api.postUnit("orders/$id/otp-request") } }, Modifier.fillMaxWidth()) {
                                Text("Yêu cầu mã OTP")
                            }
                        }
                        if (o.handoverConfirmedAt == null && o.credentialFirstViewedAt != null) {
                            OutlinedButton({ act("Đã xác nhận nhận account.") { api.postUnit("orders/$id/handover/confirm") } }, Modifier.fillMaxWidth()) {
                                Text("Xác nhận đã đăng nhập được")
                            }
                        }
                    }
                    if (isOwner && live && otpMode) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Gửi mã OTP cho người thuê", fontWeight = FontWeight.Bold)
                                OutlinedTextField(otpNote, { otpNote = it.take(64) }, singleLine = true, label = { Text("Mã OTP") }, modifier = Modifier.fillMaxWidth())
                                PrimaryButton("Gửi", enabled = otpNote.isNotBlank(), loading = busy) {
                                    act("Đã gửi mã OTP (tự hết hạn sau 10 phút).") {
                                        api.postUnit("orders/$id/otp-note", OtpNoteRequest(otpNote))
                                        otpNote = ""
                                    }
                                }
                            }
                        }
                    }
                    if (isRenter && (o.status == "PENDING_PAYMENT" || o.status == "AWAITING_OWNER_APPROVAL" || o.status == "CONFIRMED")) {
                        TextButton({ confirmCancel = true }, Modifier.fillMaxWidth()) { Text("Hủy đơn", color = PriceRed) }
                    }

                    // ---- Khiếu nại ----
                    DisputeSection(disputes.state, canOpen = (isRenter || isOwner) && (o.status == "IN_RENTAL" || o.status == "ENDED")) { openDispute = true }
                }

                if (openDispute) {
                    OpenDisputeDialog(
                        types = if (isOwner) listOf("OWNER_DAMAGE", "ACCOUNT_TAKEOVER") else listOf("RENTER_LOGIN_FAILED", "RENTER_NOT_AS_DESCRIBED"),
                        onDismiss = { openDispute = false },
                        onSubmit = { type, text ->
                            openDispute = false
                            act("Đã gửi khiếu nại.") { api.postUnit("orders/$id/disputes", OpenDisputeRequest(type, text)) }
                        },
                    )
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Hủy đơn này?") },
            text = { Text("Phí hủy (nếu có) được tính theo chính sách của game.") },
            confirmButton = {
                TextButton({
                    confirmCancel = false
                    act("Đã hủy đơn.") { api.postUnit("orders/$id/cancel") }
                }) { Text("Hủy đơn", color = PriceRed) }
            },
            dismissButton = { TextButton({ confirmCancel = false }) { Text("Giữ đơn") } },
        )
    }

    credentials?.let { c ->
        AlertDialog(
            onDismissRequest = { credentials = null },
            title = { Text("Tài khoản đăng nhập") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tên đăng nhập", fontSize = 12.sp, color = Muted)
                    Text(c.username, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Mật khẩu", fontSize = 12.sp, color = Muted)
                    Text(if (showPassword) c.password else "••••••••••", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    TextButton({ showPassword = !showPassword }) { Text(if (showPassword) "Ẩn mật khẩu" else "Hiện mật khẩu", color = Orange) }
                    c.otpNote?.let {
                        Text("Mã OTP từ chủ account", fontSize = 12.sp, color = Muted)
                        Text(it, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = PriceRed)
                    }
                    Text("Xem được đến ${fmtTime(c.availableUntil)}. Không chia sẻ thông tin này cho người khác.", fontSize = 12.sp, color = Muted)
                }
            },
            confirmButton = { TextButton({ credentials = null }) { Text("Đóng") } },
        )
    }
}

@Composable
private fun Line(k: String, v: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.sp, color = Muted)
        Text(v, fontSize = 13.sp, color = if (highlight) PriceRed else Color.Unspecified, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun DisputeSection(state: com.rentgg.app.ui.Load<List<Dispute>>, canOpen: Boolean, onOpen: () -> Unit) {
    val list = (state as? com.rentgg.app.ui.Load.Ready)?.data.orEmpty()
    if (list.isEmpty() && !canOpen) return
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Khiếu nại", fontWeight = FontWeight.Bold)
            list.forEach { d ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${disputeTypeText(d.type)} · ${d.status}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(d.description, fontSize = 12.sp, color = Muted)
                    if (d.status == "RESOLVED") {
                        Text("Khấu trừ cọc ${vnd(d.depositDeduction)} · hoàn tiền thuê ${vnd(d.rentRefund)}", fontSize = 12.sp)
                    }
                    d.decisionNote?.let { Text(it, fontSize = 12.sp, color = Muted) }
                }
            }
            if (canOpen) OutlinedButton(onOpen, Modifier.fillMaxWidth()) { Text("Gửi khiếu nại") }
        }
    }
}

@Composable
private fun OpenDisputeDialog(types: List<String>, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var type by remember { mutableStateOf(types.first()) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gửi khiếu nại") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { t -> FilterChip(type == t, { type = t }, label = { Text(disputeTypeText(t)) }) }
                OutlinedTextField(text, { text = it.take(4000) }, label = { Text("Mô tả chi tiết") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onSubmit(type, text.trim()) }, enabled = text.isNotBlank()) { Text("Gửi", color = Orange) } },
        dismissButton = { TextButton(onDismiss) { Text("Hủy") } },
    )
}
