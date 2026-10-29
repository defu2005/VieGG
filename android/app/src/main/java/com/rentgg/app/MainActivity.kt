package com.rentgg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.rentgg.app.ui.AppRoot
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.RentGgTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RentApp
        setContent {
            RentGgTheme {
                CompositionLocalProvider(LocalApi provides app.api) {
                    AppRoot(app)
                }
            }
        }
    }
}
