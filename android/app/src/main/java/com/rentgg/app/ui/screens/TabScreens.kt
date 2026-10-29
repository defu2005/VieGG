package com.rentgg.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rentgg.app.data.GameView
import com.rentgg.app.data.Me
import com.rentgg.app.data.NotificationItem
import com.rentgg.app.data.Order
import com.rentgg.app.data.PageResponse
import com.rentgg.app.ui.EmptyBox
import com.rentgg.app.ui.LoadView
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.LocalSnack
import com.rentgg.app.ui.Muted
import com.rentgg.app.ui.Orange
import com.rentgg.app.ui.OrangeTopBar
import com.rentgg.app.ui.PriceRed
import com.rentgg.app.ui.StatusChip
import com.rentgg.app.ui.Tag
import com.rentgg.app.ui.errorText
import com.rentgg.app.ui.fmtTime
import com.rentgg.app.ui.orderStatus
import com.rentgg.app.ui.rememberLoader
import com.rentgg.app.ui.vnd
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun OrdersTab(nav: NavHostController) {
    val api = LocalApi.current
    var role by rememberSaveable { mutableStateOf("RENTER") }
    val orders = rememberLoader(role) { api.get<PageResponse<Order>>("orders", mapOf("role" to role, "size" to "50")) }
    val games = rememberLoader { runCatching { api.get<List<GameView>>("games") }.getOrDefault(emptyList()) }
    val names = (games.state as? com.rentgg.app.ui.Load.Ready)?.data.orEmpty().associate { it.id to it.name }

    Column(Modifier.fillMaxSize()) {
        OrangeTopBar("Đơn thuê")
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(role == "RENTER", { role = "RENTER" }, label = { Text("Tôi thuê") })
            FilterChip(role == "OWNER", { role = "OWNER" }, label = { Text("Tôi cho thuê") })
        }
        LoadView(orders) { page ->
            if (page.content.isEmpty()) EmptyBox("Chưa có đơn nào")
            LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(page.content, key = { it.id }) { o ->
                    Card(
                        Modifier.fillMaxWidth().clickable { nav.navigate("order/${o.id}") },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(names[o.gameId] ?: "Đơn thuê", fontWeight = FontWeight.Bold)
                                StatusChip(orderStatus(o.status))
                            }
                            Text("${fmtTime(o.startAt)} → ${fmtTime(o.scheduledEndAt)}", fontSize = 12.sp, color = Muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Thanh toán", fontSize = 12.sp, color = Muted)
                                Text(vnd(o.amountToPay), color = PriceRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationsTab(nav: NavHostController, onChanged: () -> Unit) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val items = rememberLoader { api.get<PageResponse<NotificationItem>>("notifications", mapOf("size" to "50")) }

    Column(Modifier.fillMaxSize()) {
        OrangeTopBar("Thông báo")
        LoadView(items) { page ->
            if (page.content.isEmpty()) EmptyBox("Chưa có thông báo")
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(page.content, key = { it.id }) { n ->
                    val unread = n.readAt == null
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            // Thông báo liên quan đơn thuê thì mở thẳng đơn đó.
                            val orderId = (n.data?.get("orderId") as? JsonPrimitive)?.contentOrNull
                            if (orderId != null) nav.navigate("order/$orderId")
                            if (unread) scope.launch {
                                try {
                                    api.postUnit("notifications/${n.id}/read")
                                    items.reload()
                                    onChanged()
                                } catch (e: Exception) {
                                    snack(errorText(e))
                                }
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape)
                                    .background(if (unread) PriceRed else Color.Transparent),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(n.title, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
                                Text(n.body, fontSize = 13.sp, color = Muted)
                                Text(fmtTime(n.createdAt), fontSize = 11.sp, color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeTab(nav: NavHostController, onLogout: () -> Unit) {
    val api = LocalApi.current
    val me = rememberLoader { api.get<Me>("me") }

    Column(Modifier.fillMaxSize()) {
        OrangeTopBar("Của tôi")
        LoadView(me) { u ->
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(Orange), contentAlignment = Alignment.Center) {
                            Text(u.displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(u.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(u.email, fontSize = 12.sp, color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Tag(if (u.kycStatus == "VERIFIED") "Đã xác minh" else "Chưa xác minh", if (u.kycStatus == "VERIFIED") Color(0xFF1FA15C) else Muted)
                                if (u.status != "ACTIVE") Tag(u.status, PriceRed)
                            }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column {
                        MenuRow(Icons.Filled.AccountBalanceWallet, "Ví & rút tiền") { nav.navigate("wallet") }
                        MenuRow(Icons.Filled.SportsEsports, "Account cho thuê của tôi") { nav.navigate("mylistings") }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    MenuRow(Icons.AutoMirrored.Filled.Logout, "Đăng xuất", onClick = onLogout)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = Orange)
        Text(text, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
}
