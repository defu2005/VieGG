package com.rentgg.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rentgg.app.data.CreateOrderRequest
import com.rentgg.app.data.ListingDetail
import com.rentgg.app.data.Order
import com.rentgg.app.ui.GameTile
import com.rentgg.app.ui.LoadView
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.LocalSnack
import com.rentgg.app.ui.Muted
import com.rentgg.app.ui.OrangeTopBar
import com.rentgg.app.ui.PriceRed
import com.rentgg.app.ui.PriceText
import com.rentgg.app.ui.PrimaryButton
import com.rentgg.app.ui.Tag
import com.rentgg.app.ui.durationText
import com.rentgg.app.ui.endModeText
import com.rentgg.app.ui.errorText
import com.rentgg.app.ui.fmtTime
import com.rentgg.app.ui.rememberLoader
import com.rentgg.app.ui.vnd
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val startOptions = listOf("Ngay bây giờ" to 5L, "Sau 1 giờ" to 60L, "Sau 3 giờ" to 180L, "Sau 6 giờ" to 360L, "Sau 12 giờ" to 720L)
private val durationOptions = listOf(60, 120, 180, 300, 480, 720, 1440)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ListingDetailScreen(id: String, nav: NavHostController) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val detail = rememberLoader(id) { api.get<ListingDetail>("listings/$id") }
    var startIdx by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(60) }
    var busy by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf<Instant?>(null) }
    var pickStep by remember { mutableIntStateOf(0) } // 0 = đóng, 1 = chọn ngày, 2 = chọn giờ
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    if (pickStep == 1) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickStep = 0 },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickedDate = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        pickStep = if (pickedDate != null) 2 else 0
                    },
                ) { Text("Tiếp") }
            },
            dismissButton = { TextButton({ pickStep = 0 }) { Text("Hủy") } },
        ) { DatePicker(state) }
    }
    if (pickStep == 2) {
        val time = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickStep = 0 },
            title = { Text("Chọn giờ bắt đầu") },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton({
                    val at = pickedDate!!.atTime(time.hour, time.minute).atZone(ZoneId.systemDefault()).toInstant()
                    if (at.isBefore(Instant.now().plus(1, ChronoUnit.MINUTES))) {
                        snack("Giờ bắt đầu phải ở tương lai.")
                    } else {
                        custom = at
                        startIdx = -1
                        pickStep = 0
                    }
                }) { Text("Xong") }
            },
            dismissButton = { TextButton({ pickStep = 0 }) { Text("Hủy") } },
        )
    }

    Scaffold(topBar = { OrangeTopBar("Chi tiết account", onBack = { nav.popBackStack() }) }, containerColor = com.rentgg.app.ui.Surface0) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LoadView(detail) { d ->
                val l = d.listing
                val p = d.policy
                val options = durationOptions.filter { it in p.minDurationMinutes..p.maxDurationMinutes }
                    .ifEmpty { listOf(p.minDurationMinutes) }
                if (duration !in options) duration = options.first()
                val estimate = l.pricePerHour * duration / 60

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                GameTile(l.title, 56)
                                Text(l.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (l.autoAccept) Tag("Tự nhận đơn", Color(0xFF1FA15C))
                                Tag("Cọc ${vnd(l.depositAmount)}")
                                Tag(endModeText(l.endMode), Color(0xFF2F7DF6))
                                l.attributes?.forEach { (k, v) -> Tag("$k: ${v.toString().trim('"')}", Muted) }
                            }
                            PriceText(l.pricePerHour)
                            l.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp) }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Chọn thời gian thuê", fontWeight = FontWeight.Bold)
                            Text("Bắt đầu", fontSize = 12.sp, color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                startOptions.forEachIndexed { i, (label, _) ->
                                    FilterChip(startIdx == i, { startIdx = i }, label = { Text(label) })
                                }
                                FilterChip(
                                    startIdx == -1,
                                    { pickStep = 1 },
                                    label = { Text(custom?.takeIf { startIdx == -1 }?.let { fmtTime(it.toString()) } ?: "Chọn giờ khác…") },
                                )
                            }
                            Text("Thời lượng", fontSize = 12.sp, color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                options.forEach { m -> FilterChip(duration == m, { duration = m }, label = { Text(durationText(m)) }) }
                            }
                            if (d.busyPeriods.isNotEmpty()) {
                                Text("Đã có người thuê:", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                                d.busyPeriods.forEach { Text("• ${fmtTime(it.startAt)} → ${fmtTime(it.endAt)}", fontSize = 12.sp, color = Muted) }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Quy định thuê", fontWeight = FontWeight.Bold)
                            PolicyLine("Thời lượng", "${durationText(p.minDurationMinutes)} – ${durationText(p.maxDurationMinutes)}")
                            PolicyLine("Hủy miễn phí", "${p.renterFreeCancelMinutes} phút sau khi đặt")
                            PolicyLine("Cửa sổ khiếu nại", "${p.complaintWindowMinutes} phút")
                            PolicyLine("Giữ cọc", "${p.depositHoldHours} giờ sau khi kết thúc")
                            PolicyLine("Độ tuổi tối thiểu", "${p.minAge}+")
                            PolicyLine("Bàn giao", if (p.handoverMode == "OWNER_ASSISTED_OTP") "Chủ gửi mã OTP khi đăng nhập" else "Nhận tài khoản/mật khẩu sau thanh toán")
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Tạm tính (chưa gồm cọc)", fontSize = 11.sp, color = Muted)
                            Text(vnd(estimate), color = PriceRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("+ cọc ${vnd(l.depositAmount)}", fontSize = 11.sp, color = Muted)
                        }
                        PrimaryButton(
                            if (d.bookable) "Thuê ngay" else "Không thể đặt",
                            enabled = d.bookable && (startIdx != -1 || custom != null),
                            loading = busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            busy = true
                            scope.launch {
                                try {
                                    val start = custom?.takeIf { startIdx == -1 }
                                        ?: Instant.now().plus(startOptions[startIdx].second, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
                                    val order = api.post<CreateOrderRequest, Order>(
                                        "orders", CreateOrderRequest(l.id, start.toString(), duration), idempotent = true,
                                    )
                                    nav.navigate("order/${order.id}") { popUpTo("main") }
                                } catch (e: Exception) {
                                    snack(errorText(e))
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.sp, color = Muted)
        Text(v, fontSize = 13.sp)
    }
}
