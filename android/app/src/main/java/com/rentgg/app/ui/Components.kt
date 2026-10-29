package com.rentgg.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
}

/** Trạng thái tải một lần, có thể nạp lại bằng [Loader.reload]. */
class Loader<T> internal constructor(val state: Load<T>, val reload: () -> Unit)

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, tick) {
        if (state !is Load.Ready) state = Load.Loading
        state = try {
            Load.Ready(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Error(errorText(e))
        }
    }
    return Loader(state) { tick++ }
}

@Composable
fun <T> LoadView(loader: Loader<T>, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Orange) }
        is Load.Error -> ErrorBox(s.message, loader.reload)
        is Load.Ready -> content(s.data)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                Text("Thử lại")
            }
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
        Text(text, color = Muted, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrangeTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Orange,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
    )
}

@Composable
fun Tag(text: String, color: Color = Orange, filled: Boolean = false) {
    Text(
        text,
        fontSize = 11.sp,
        color = if (filled) Color.White else color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun StatusChip(style: StatusStyle) = Tag(style.label, style.color)

/** Giá kiểu "30.000đ/giờ" với số lớn màu đỏ cam, đơn vị nhỏ — nhận diện giống các sàn TQ. */
@Composable
fun PriceText(perHour: Long) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(vnd(perHour), color = PriceRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("/giờ", color = PriceRed, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
    }
}

/** Ô vuông chữ cái đầu có màu theo tên: thay cho icon game (backend chưa có ảnh). */
@Composable
fun GameTile(name: String, size: Int = 44) {
    val palette = listOf(Color(0xFFFF6A00), Color(0xFF2F7DF6), Color(0xFF8E44AD), Color(0xFF1FA15C), Color(0xFFE91E63), Color(0xFF00A8A8))
    val c = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(c),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 2.2).sp)
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, loading: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.Bold)
    }
}
