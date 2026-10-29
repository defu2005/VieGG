package com.rentgg.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Bảng màu lấy cảm hứng từ các sàn thuê account Trung Quốc (租号玩…): cam nhấn, nền xám nhạt, thẻ trắng bo góc, giá màu đỏ cam.
val Orange = Color(0xFFFF6A00)
val OrangeSoft = Color(0xFFFFF1E6)
val PriceRed = Color(0xFFFF4D2E)
val Ink = Color(0xFF222222)
val Muted = Color(0xFF8A8A93)
val Surface0 = Color(0xFFF6F6F8)
val Green = Color(0xFF1FA15C)
val Blue = Color(0xFF2F7DF6)

private val scheme = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = OrangeSoft,
    onPrimaryContainer = Color(0xFF8A3700),
    background = Surface0,
    surface = Color.White,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    outlineVariant = Color(0xFFE6E6EB),
    error = Color(0xFFD93025),
)

@Composable
fun RentGgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
