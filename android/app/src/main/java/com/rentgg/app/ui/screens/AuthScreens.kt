package com.rentgg.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentgg.app.data.RegisterRequest
import com.rentgg.app.ui.LocalApi
import com.rentgg.app.ui.LocalSnack
import com.rentgg.app.ui.Orange
import com.rentgg.app.ui.OrangeTopBar
import com.rentgg.app.ui.PrimaryButton
import com.rentgg.app.ui.PriceRed
import com.rentgg.app.ui.errorText
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onRegister: () -> Unit) {
    val api = LocalApi.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Box(
            Modifier.fillMaxWidth().height(220.dp)
                .background(Brush.verticalGradient(listOf(Orange, PriceRed))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VieGG", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Text("Thuê account game · Nhận ngay · Có bảo hộ", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
            }
        }
        Column(
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Mật khẩu") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme_error()) }
            PrimaryButton("Đăng nhập", enabled = email.isNotBlank() && password.isNotBlank(), loading = busy) {
                busy = true
                error = null
                scope.launch {
                    try {
                        api.login(email, password)
                        onLoggedIn()
                    } catch (e: Exception) {
                        error = errorText(e)
                    } finally {
                        busy = false
                    }
                }
            }
            TextButton(onRegister, Modifier.align(Alignment.CenterHorizontally)) {
                Text("Chưa có tài khoản? Đăng ký", color = Orange)
            }
        }
    }
}

@Composable
private fun MaterialTheme_error() = androidx.compose.material3.MaterialTheme.colorScheme.error

@Composable
fun RegisterScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val api = LocalApi.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val dobValue = runCatching { LocalDate.parse(dob) }.getOrNull()
    val valid = email.contains("@") && name.isNotBlank() && password.length >= 10 && dobValue != null

    Scaffold(topBar = { OrangeTopBar("Đăng ký", onBack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Tên hiển thị") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu (tối thiểu 10 ký tự)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(dob, { dob = it.trim() }, label = { Text("Ngày sinh (YYYY-MM-DD)") }, singleLine = true,
                supportingText = { Text("Phải đủ 18 tuổi") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme_error()) }
            PrimaryButton("Đăng ký", enabled = valid, loading = busy) {
                busy = true
                error = null
                scope.launch {
                    try {
                        api.register(RegisterRequest(email, password, name, dobValue.toString()))
                        snack("Đăng ký thành công, hãy đăng nhập.")
                        onDone()
                    } catch (e: Exception) {
                        error = errorText(e)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
