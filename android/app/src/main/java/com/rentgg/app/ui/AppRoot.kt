package com.rentgg.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rentgg.app.RentApp
import com.rentgg.app.data.ApiClient
import com.rentgg.app.ui.screens.AddAccountScreen
import com.rentgg.app.ui.screens.CreateListingScreen
import com.rentgg.app.ui.screens.ListingDetailScreen
import com.rentgg.app.ui.screens.LoginScreen
import com.rentgg.app.ui.screens.MainScreen
import com.rentgg.app.ui.screens.MyListingsScreen
import com.rentgg.app.ui.screens.OrderDetailScreen
import com.rentgg.app.ui.screens.RegisterScreen
import com.rentgg.app.ui.screens.WalletScreen
import kotlinx.coroutines.launch

val LocalApi = compositionLocalOf<ApiClient> { error("ApiClient chưa được cung cấp") }

/** Snackbar dùng chung cho toàn app: các màn hình gọi `snack("...")` để báo kết quả thao tác. */
val LocalSnack = compositionLocalOf<(String) -> Unit> { {} }

@Composable
fun AppRoot(app: RentApp) {
    val nav = rememberNavController()
    val snackState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack: (String) -> Unit = { msg -> scope.launch { snackState.showSnackbar(msg) } }
    var start by remember { mutableStateOf(if (app.tokens.hasSession) "main" else "login") }

    // Refresh token hết hạn: quay về màn đăng nhập, xóa toàn bộ back stack.
    app.api.onSessionExpired = {
        scope.launch {
            nav.navigate("login") { popUpTo(0) }
            snackState.showSnackbar("Phiên đăng nhập đã hết hạn.")
        }
    }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSnack provides snack) {
            NavHost(nav, startDestination = start) {
                composable("login") {
                    LoginScreen(
                        onLoggedIn = { nav.navigate("main") { popUpTo(0) } },
                        onRegister = { nav.navigate("register") },
                    )
                }
                composable("register") {
                    RegisterScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
                }
                composable("main") { MainScreen(nav, onLogout = { logout(app, nav) }) }
                composable("listing/{id}") { e ->
                    ListingDetailScreen(e.arguments!!.getString("id")!!, nav)
                }
                composable("order/{id}") { e ->
                    OrderDetailScreen(e.arguments!!.getString("id")!!, nav)
                }
                composable("wallet") { WalletScreen(onBack = { nav.popBackStack() }) }
                composable("mylistings") { MyListingsScreen(nav) }
                composable("addaccount") { AddAccountScreen(onBack = { nav.popBackStack() }) }
                composable("createlisting/{accountId}/{gameId}") { e ->
                    CreateListingScreen(
                        e.arguments!!.getString("accountId")!!,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
        SnackbarHost(snackState, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}

private fun logout(app: RentApp, nav: NavHostController) {
    app.api.logout()
    nav.navigate("login") { popUpTo(0) }
}
