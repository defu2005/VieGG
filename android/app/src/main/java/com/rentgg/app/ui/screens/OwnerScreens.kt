package com.rentgg.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rentgg.app.data.CreateGameAccountRequest
import com.rentgg.app.data.CreateListingRequest
import com.rentgg.app.data.GameAccount
import com.rentgg.app.data.GameView
import com.rentgg.app.data.Listing
import com.rentgg.app.data.PageResponse
import com.rentgg.app.ui.EmptyBox
import com.rentgg.app.ui.LoadView
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.LocalSnack
import com.rentgg.app.ui.Muted
import com.rentgg.app.ui.Orange
import com.rentgg.app.ui.OrangeTopBar
import com.rentgg.app.ui.PriceText
import com.rentgg.app.ui.PrimaryButton
import com.rentgg.app.ui.StatusChip
import com.rentgg.app.ui.Surface0
import com.rentgg.app.ui.Tag
import com.rentgg.app.ui.endModeText
import com.rentgg.app.ui.errorText
import com.rentgg.app.ui.listingStatus
import com.rentgg.app.ui.rememberLoader
import com.rentgg.app.ui.vnd
import kotlinx.coroutines.launch

@Composable
fun MyListingsScreen(nav: NavHostController) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val data = rememberLoader {
        Triple(
            api.get<PageResponse<GameAccount>>("game-accounts", mapOf("size" to "100")).content,
            api.get<PageResponse<Listing>>("listings/mine", mapOf("size" to "100")).content,
            runCatching { api.get<List<GameView>>("games") }.getOrDefault(emptyList()),
        )
    }

    fun act(ok: String, block: suspend () -> Unit) = scope.launch {
        try {
            block()
            snack(ok)
            data.reload()
        } catch (e: Exception) {
            snack(errorText(e))
        }
    }

    Scaffold(
        topBar = { OrangeTopBar("Account cho thuê", onBack = { nav.popBackStack() }) },
        containerColor = Surface0,
        floatingActionButton = {
            FloatingActionButton({ nav.navigate("addaccount") }, containerColor = Orange, contentColor = Color.White) { Icon(Icons.Filled.Add, "Thêm account") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LoadView(data) { (accounts, listings, games) ->
                val gameName = { id: String -> games.firstOrNull { it.id == id }?.name ?: "Game" }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Account game", fontWeight = FontWeight.Bold)
                    if (accounts.isEmpty()) EmptyBox("Chưa có account. Nhấn + để thêm.")
                    accounts.forEach { a ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${gameName(a.gameId)}${a.serverRegion?.let { " · $it" } ?: ""}${a.accountKey?.let { " · $it" } ?: ""}", fontWeight = FontWeight.Bold)
                                    Tag(a.securityState, Muted)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton({ nav.navigate("createlisting/${a.id}/${a.gameId}") }) { Text("Tạo listing", color = Orange) }
                                    TextButton({ act("Đã lấy lại account.") { api.postUnit("game-accounts/${a.id}/reclaim") } }) { Text("Lấy lại") }
                                }
                            }
                        }
                    }

                    Text("Listing của tôi", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    if (listings.isEmpty()) EmptyBox("Chưa có listing")
                    listings.forEach { l ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(l.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    StatusChip(listingStatus(l.status))
                                }
                                PriceText(l.pricePerHour)
                                Text("Cọc ${vnd(l.depositAmount)} · ${endModeText(l.endMode)}", fontSize = 12.sp, color = Muted)
                                l.reviewNote?.takeIf { it.isNotBlank() }?.let { Text("Ghi chú duyệt: $it", fontSize = 12.sp, color = Muted) }
                                Row {
                                    if (l.status == "DRAFT" || l.status == "PAUSED") {
                                        TextButton({ act("Đã gửi duyệt.") { api.postUnit("listings/${l.id}/submit") } }) { Text("Gửi duyệt", color = Orange) }
                                    }
                                    if (l.status == "ACTIVE") {
                                        TextButton({ act("Đã tạm dừng listing.") { api.postUnit("listings/${l.id}/pause") } }) { Text("Tạm dừng") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddAccountScreen(onBack: () -> Unit) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val games = rememberLoader { api.get<List<GameView>>("games").filter { it.tosStatus == "ALLOWED" } }
    var gameId by remember { mutableStateOf<String?>(null) }
    var region by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var accountKey by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(false) }
    var twoFactor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = { OrangeTopBar("Thêm account game", onBack) }, containerColor = Surface0) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LoadView(games) { list ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Game", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { g -> FilterChip(gameId == g.id, { gameId = g.id }, label = { Text(g.name) }) }
                    }
                    val rule = list.firstOrNull { it.id == gameId }?.accountKey
                    val keyLabel = list.firstOrNull { it.id == gameId }?.accountKeyLabel?.ifBlank { null } ?: "ID trong game"
                    val needsKey = rule?.needsKey == true
                    val needsRegion = needsKey && rule?.perServer == true
                    OutlinedTextField(region, { region = it }, label = { Text(if (needsRegion) "Server / khu vực (bắt buộc)" else "Server / khu vực") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (needsKey) {
                        OutlinedTextField(accountKey, { accountKey = it.take(200) }, label = { Text(keyLabel) },
                            placeholder = { rule?.example?.let { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Mỗi $keyLabel chỉ được đăng một lần trên VieGG.", fontSize = 12.sp, color = Muted)
                    }
                    OutlinedTextField(username, { username = it }, label = { Text("Tên đăng nhập game") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu game") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text("Bạn vẫn giữ quyền khôi phục account bằng:", fontSize = 13.sp, color = Muted)
                    CheckRow("Email khôi phục của tôi", email) { email = it }
                    CheckRow("Số điện thoại khôi phục của tôi", phone) { phone = it }
                    CheckRow("Xác thực 2 bước do tôi giữ", twoFactor) { twoFactor = it }
                    PrimaryButton("Lưu account", enabled = gameId != null && username.isNotBlank() && password.isNotBlank()
                        && (!needsKey || accountKey.isNotBlank()) && (!needsRegion || region.isNotBlank()), loading = busy) {
                        busy = true
                        scope.launch {
                            try {
                                api.postUnit(
                                    "game-accounts",
                                    CreateGameAccountRequest(gameId!!, region.ifBlank { null }, username, password, email, phone, twoFactor,
                                        accountKey = if (needsKey) accountKey.trim() else null),
                                )
                                snack("Đã thêm account. Chờ xác minh khôi phục.")
                                onBack()
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

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, fontSize = 14.sp)
    }
}

private val endModes = listOf("FORCE_AT_END", "GRACE_FOR_CURRENT_MATCH", "STAY_ONLINE_UNTIL_RECLAIM")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateListingScreen(accountId: String, onBack: () -> Unit) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var deposit by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(false) }
    var endMode by remember { mutableStateOf(endModes.first()) }
    var busy by remember { mutableStateOf(false) }
    val p = price.toLongOrNull()
    val d = deposit.toLongOrNull()

    Scaffold(topBar = { OrangeTopBar("Tạo listing", onBack) }, containerColor = Surface0) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(150) }, label = { Text("Tiêu đề (rank, skin nổi bật…)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it.take(5000) }, label = { Text("Mô tả") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(price, { price = it.filter(Char::isDigit) }, label = { Text("Giá mỗi giờ (đ)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deposit, { deposit = it.filter(Char::isDigit) }, label = { Text("Tiền cọc (đ)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tự động nhận đơn")
                Switch(auto, { auto = it })
            }
            Text("Khi hết giờ thuê", fontSize = 13.sp, color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                endModes.forEach { m -> FilterChip(endMode == m, { endMode = m }, label = { Text(endModeText(m)) }) }
            }
            PrimaryButton("Lưu nháp", enabled = title.isNotBlank() && p != null && p > 0 && d != null && d > 0, loading = busy) {
                busy = true
                scope.launch {
                    try {
                        api.postUnit(
                            "listings",
                            CreateListingRequest(accountId, title.trim(), desc.ifBlank { null }, p!!, d!!, auto, endMode),
                        )
                        snack("Đã tạo listing nháp. Hãy gửi duyệt.")
                        onBack()
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
