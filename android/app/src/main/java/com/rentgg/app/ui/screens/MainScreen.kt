package com.rentgg.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rentgg.app.data.GameView
import com.rentgg.app.data.Listing
import com.rentgg.app.data.PageResponse
import com.rentgg.app.ui.EmptyBox
import com.rentgg.app.ui.GameTile
import com.rentgg.app.ui.LoadView
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.Muted
import com.rentgg.app.ui.Orange
import com.rentgg.app.ui.OrangeSoft
import com.rentgg.app.ui.PriceText
import com.rentgg.app.ui.Tag
import com.rentgg.app.ui.endModeText
import com.rentgg.app.ui.errorText
import com.rentgg.app.ui.rememberLoader
import com.rentgg.app.ui.vnd
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState

private enum class Tab(val label: String) { Home("Trang chủ"), Orders("Đơn thuê"), Inbox("Thông báo"), Me("Của tôi") }

@Composable
fun MainScreen(nav: NavHostController, onLogout: () -> Unit) {
    val api = LocalApi.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val unread = rememberLoader(tab) {
        runCatching { api.get<PageResponse<com.rentgg.app.data.NotificationItem>>("notifications", mapOf("unread" to "true", "size" to "1")).totalElements }
            .getOrDefault(0L)
    }
    val unreadCount = (unread.state as? com.rentgg.app.ui.Load.Ready)?.data ?: 0L

    Scaffold(
        containerColor = com.rentgg.app.ui.Surface0,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            val icon = when (t) {
                                Tab.Home -> Icons.Filled.Home
                                Tab.Orders -> Icons.AutoMirrored.Filled.ReceiptLong
                                Tab.Inbox -> Icons.Filled.Notifications
                                Tab.Me -> Icons.Filled.AccountCircle
                            }
                            if (t == Tab.Inbox && unreadCount > 0) {
                                BadgedBox(badge = { Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } }) { Icon(icon, t.label) }
                            } else Icon(icon, t.label)
                        },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orange,
                            selectedTextColor = Orange,
                            indicatorColor = OrangeSoft,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeTab(nav)
                Tab.Orders -> OrdersTab(nav)
                Tab.Inbox -> NotificationsTab(nav, onChanged = unread.reload)
                Tab.Me -> MeTab(nav, onLogout)
            }
        }
    }
}

private enum class Sort(val label: String) { Default("Mặc định"), PriceAsc("Giá thấp → cao"), PriceDesc("Giá cao → thấp") }

@Composable
private fun HomeTab(nav: NavHostController) {
    val api = LocalApi.current
    var query by rememberSaveable { mutableStateOf("") }
    var gameId by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.Default) }
    var autoOnly by rememberSaveable { mutableStateOf(false) }

    val games = rememberLoader { api.get<List<GameView>>("games").filter { it.tosStatus == "ALLOWED" } }
    val listings = rememberLoader(gameId) {
        api.get<PageResponse<Listing>>("listings/search", mapOf("gameId" to gameId, "size" to "50"))
    }
    val gameList = (games.state as? com.rentgg.app.ui.Load.Ready)?.data.orEmpty()
    val gameName = { id: String -> gameList.firstOrNull { it.id == id }?.name ?: "Game" }

    Column(Modifier.fillMaxSize()) {
        // Thanh tìm kiếm nền cam, giống đầu trang chủ của các sàn thuê account.
        Column(Modifier.fillMaxWidth().background(Orange).padding(horizontal = 12.dp, vertical = 10.dp)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Tìm tên account, rank, skin…", color = Muted) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LoadView(listings) { page ->
            val shown = page.content
                .filter { query.isBlank() || it.title.contains(query, true) || (it.description ?: "").contains(query, true) }
                .filter { !autoOnly || it.autoAccept }
                .let {
                    when (sort) {
                        Sort.Default -> it
                        Sort.PriceAsc -> it.sortedBy { l -> l.pricePerHour }
                        Sort.PriceDesc -> it.sortedByDescending { l -> l.pricePerHour }
                    }
                }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    if (gameList.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            LazyRow(
                                Modifier.padding(vertical = 12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                item { GameCell("Tất cả", gameId == null) { gameId = null } }
                                items(gameList, key = { it.id }) { g -> GameCell(g.name, gameId == g.id) { gameId = g.id } }
                            }
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Sort.entries.toList()) { s ->
                            FilterChip(sort == s, { sort = s }, label = { Text(s.label) }, colors = chipColors())
                        }
                        item { FilterChip(autoOnly, { autoOnly = !autoOnly }, label = { Text("Tự nhận đơn") }, colors = chipColors()) }
                    }
                }
                if (shown.isEmpty()) item { EmptyBox("Chưa có account phù hợp") }
                items(shown, key = { it.id }) { l ->
                    ListingCard(l, gameName(l.gameId)) { nav.navigate("listing/${l.id}") }
                }
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = OrangeSoft,
    selectedLabelColor = Orange,
)

@Composable
private fun GameCell(name: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        if (name == "Tất cả") {
            Box(Modifier.width(44.dp).padding(0.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(44.dp).background(if (selected) Orange else Color(0xFFEDEDF0), RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("All", color = if (selected) Color.White else Muted, fontWeight = FontWeight.Bold) }
            }
        } else GameTile(name)
        Text(
            name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) Orange else Color(0xFF444444),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun ListingCard(l: Listing, gameName: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameTile(gameName, 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(l.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(gameName, Color(0xFF2F7DF6))
                    if (l.autoAccept) Tag("Tự nhận đơn", Color(0xFF1FA15C))
                    Tag("Cọc ${vnd(l.depositAmount)}")
                }
                // Thuộc tính tự do do chủ account khai (rank, skin…): hiển thị tối đa 3 mục dạng thẻ.
                l.attributes?.entries?.take(3)?.let { attrs ->
                    if (attrs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        attrs.forEach { (k, v) -> Tag("$k: ${v.toString().trim('"')}", Muted) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    PriceText(l.pricePerHour)
                    Text(endModeText(l.endMode), fontSize = 10.sp, color = Muted)
                }
            }
        }
    }
}
