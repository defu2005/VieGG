package com.rentgg.app.data

import com.rentgg.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** Lỗi gốc từ backend: {code, message, details}. */
@kotlinx.serialization.Serializable
private data class ErrorBody(val code: String? = null, val message: String? = null)

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Nơi giữ cặp token; tách riêng để test JVM không cần Android Context. */
interface TokenStorage {
    val accessToken: String?
    val refreshToken: String?
    val hasSession: Boolean
    fun save(access: String, refresh: String)
    fun clear()
}

/**
 * Client HTTP mỏng cho API /api/v1. Tự gắn Bearer, tự refresh một lần khi 401
 * (dùng chung giữa các request song song), và gọi [onSessionExpired] khi refresh thất bại.
 */
class ApiClient(private val tokens: TokenStorage, baseUrl: String = BuildConfig.API_BASE_URL) {
    var onSessionExpired: (() -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val base = baseUrl.toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Any()

    suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T =
        call("GET", path, query, null, null, serializer())

    suspend inline fun <reified B, reified T> post(path: String, body: B, idempotent: Boolean = false): T =
        call("POST", path, emptyMap(), AppJson.encodeToString(serializer<B>(), body), idKey(idempotent), serializer())

    suspend inline fun <reified T> post(path: String, idempotent: Boolean = false): T =
        call("POST", path, emptyMap(), null, idKey(idempotent), serializer())

    suspend inline fun <reified B> postUnit(path: String, body: B, idempotent: Boolean = false) {
        call("POST", path, emptyMap(), AppJson.encodeToString(serializer<B>(), body), idKey(idempotent), serializer<Unit>())
    }

    suspend fun postUnit(path: String) {
        call("POST", path, emptyMap(), null, null, serializer<Unit>())
    }

    /** Mỗi hành động của người dùng một key mới; chỉ dùng lại khi retry cùng hành động. */
    fun idKey(enabled: Boolean): String? = if (enabled) UUID.randomUUID().toString() else null

    suspend fun <T> call(
        method: String,
        path: String,
        query: Map<String, String?>,
        json: String?,
        idempotencyKey: String?,
        serializer: KSerializer<T>,
        anonymous: Boolean = false,
    ): T = withContext(Dispatchers.IO) {
        var res = exec(method, path, query, json, idempotencyKey, anonymous)
        if (res.code == 401 && !anonymous && tokens.hasSession) {
            if (refresh()) {
                res.close()
                res = exec(method, path, query, json, idempotencyKey, anonymous)
            } else {
                onSessionExpired?.invoke()
            }
        }
        res.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = runCatching { AppJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                throw ApiException(r.code, err?.code ?: "UNKNOWN", err?.message ?: "Lỗi máy chủ (${r.code}).")
            }
            if (text.isBlank()) {
                @Suppress("UNCHECKED_CAST")
                return@use Unit as T
            }
            try {
                AppJson.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw ApiException(r.code, "INVALID_RESPONSE", "Phản hồi từ máy chủ không hợp lệ.")
            }
        }
    }

    private fun exec(
        method: String,
        path: String,
        query: Map<String, String?>,
        json: String?,
        idempotencyKey: String?,
        anonymous: Boolean,
    ): okhttp3.Response {
        val url = base.newBuilder().apply {
            addPathSegments(path.trimStart('/'))
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).method(method, json?.toRequestBody(jsonType) ?: if (method == "GET") null else ByteArray(0).toRequestBody(null))
        idempotencyKey?.let { req.header("Idempotency-Key", it) }
        if (!anonymous) tokens.accessToken?.let { req.header("Authorization", "Bearer $it") }
        try {
            return http.newCall(req.build()).execute()
        } catch (e: IOException) {
            throw ApiException(0, "NETWORK", "Không kết nối được máy chủ. Kiểm tra mạng và thử lại.")
        }
    }

    private fun refresh(): Boolean = synchronized(refreshLock) {
        val current = tokens.refreshToken ?: return false
        val body = AppJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(current))
        val req = Request.Builder().url(base.newBuilder().addPathSegments("auth/refresh").build())
            .post(body.toRequestBody(jsonType)).build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    tokens.clear()
                    return false
                }
                val t = AppJson.decodeFromString(TokenResponse.serializer(), r.body?.string().orEmpty())
                tokens.save(t.accessToken, t.refreshToken)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    // ---- Auth (ẩn danh) ----

    suspend fun login(email: String, password: String) {
        val t = call(
            "POST", "auth/login", emptyMap(),
            AppJson.encodeToString(LoginRequest.serializer(), LoginRequest(email, password)),
            null, TokenResponse.serializer(), anonymous = true,
        )
        tokens.save(t.accessToken, t.refreshToken)
    }

    suspend fun register(req: RegisterRequest) {
        call(
            "POST", "auth/register", emptyMap(),
            AppJson.encodeToString(RegisterRequest.serializer(), req),
            null, serializer<Unit>(), anonymous = true,
        )
    }

    fun logout() = tokens.clear()
}

