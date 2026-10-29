package com.rentgg.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.rentgg.app.data.Balances
import com.rentgg.app.data.Deposit
import com.rentgg.app.data.PageResponse
import com.rentgg.app.data.WithdrawRequest
import com.rentgg.app.data.Withdrawal
import com.rentgg.app.ui.LoadView
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.LocalSnack
import com.rentgg.app.ui.Muted
import com.rentgg.app.ui.Orange
import com.rentgg.app.ui.OrangeTopBar
import com.rentgg.app.ui.PriceRed
import com.rentgg.app.ui.PrimaryButton
import com.rentgg.app.ui.Tag
import com.rentgg.app.ui.errorText
import com.rentgg.app.ui.fmtTime
import com.rentgg.app.ui.rememberLoader
import com.rentgg.app.ui.vnd
import kotlinx.coroutines.launch

@Composable
fun WalletScreen(onBack: () -> Unit) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val data = rememberLoader {
        Triple(
            api.get<Balances>("wallet/balances"),
            api.get<PageResponse<Withdrawal>>("wallet/withdrawals", mapOf("size" to "50")).content,
            api.get<PageResponse<Deposit>>("wallet/deposits", mapOf("size" to "50")).content,
        )
    }
    var showWithdraw by remember { mutableStateOf(false) }

    Scaffold(topBar = { OrangeTopBar("Ví của tôi", onBack) }, containerColor = com.rentgg.app.ui.Surface0) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LoadView(data) { (b, withdrawals, deposits) ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.horizontalGradient(listOf(Orange, PriceRed)), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Số dư có thể rút", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                        Text(vnd(b.available), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row2("Đang giữ chờ đánh giá", vnd(b.reviewHold))
                            Row2("Đang giữ do khiếu nại", vnd(b.disputeHold))
                            Row2("Đang xử lý chuyển khoản", vnd(b.payoutProcessing))
                        }
                    }
                    PrimaryButton("Rút tiền", enabled = b.available > 0) { showWithdraw = true }

                    if (withdrawals.isNotEmpty()) {
                        Text("Lịch sử rút tiền", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        withdrawals.forEach { w ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(vnd(w.amount), fontWeight = FontWeight.Bold)
                                        Text(fmtTime(w.createdAt), fontSize = 11.sp, color = Muted)
                                        w.failureReason?.let { Text(it, fontSize = 11.sp, color = PriceRed) }
                                    }
                                    Tag(w.status, Orange)
                                }
                            }
                        }
                    }
                    if (deposits.isNotEmpty()) {
                        Text("Tiền cọc theo đơn", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        deposits.forEach { d ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(vnd(d.amount), fontWeight = FontWeight.Bold)
                                        Text("Giữ đến ${fmtTime(d.holdUntil)}", fontSize = 11.sp, color = Muted)
                                    }
                                    Tag(d.status, Muted)
                                }
                            }
                        }
                    }
                }

                if (showWithdraw) {
                    WithdrawDialog(
                        max = b.available,
                        onDismiss = { showWithdraw = false },
                        onSubmit = { req ->
                            showWithdraw = false
                            scope.launch {
                                try {
                                    api.postUnit("wallet/withdrawals", req, idempotent = true)
                                    snack("Đã gửi yêu cầu rút tiền.")
                                    data.reload()
                                } catch (e: Exception) {
                                    snack(errorText(e))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Row2(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.sp, color = Muted)
        Text(v, fontSize = 13.sp)
    }
}

@Composable
private fun WithdrawDialog(max: Long, onDismiss: () -> Unit, onSubmit: (WithdrawRequest) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    val amt = amount.toLongOrNull()
    val valid = amt != null && amt in 1..max && bank.isNotBlank() && number.matches(Regex("[0-9]{6,20}")) && holder.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rút tiền") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Số tiền (tối đa ${vnd(max)})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(bank, { bank = it }, label = { Text("Ngân hàng") }, singleLine = true)
                OutlinedTextField(number, { number = it.filter(Char::isDigit) }, label = { Text("Số tài khoản") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(holder, { holder = it }, label = { Text("Tên chủ tài khoản") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ onSubmit(WithdrawRequest(amt!!, bank.trim(), number, holder.trim())) }, enabled = valid) { Text("Rút", color = Orange) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Hủy") } },
    )
}
