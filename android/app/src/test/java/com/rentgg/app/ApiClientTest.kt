package com.rentgg.app

import com.rentgg.app.data.ApiClient
import com.rentgg.app.data.ApiException
import com.rentgg.app.data.Me
import com.rentgg.app.data.TokenStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class FakeTokens(var a: String? = "old", var r: String? = "refresh1") : TokenStorage {
    override val accessToken get() = a
    override val refreshToken get() = r
    override val hasSession get() = a != null
    override fun save(access: String, refresh: String) { a = access; r = refresh }
    override fun clear() { a = null; r = null }
}

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val tokens = FakeTokens()
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = ApiClient(tokens, server.url("/api/v1/").toString())
    }

    @After fun tearDown() = server.shutdown()

    private val meJson = """{"id":"u1","email":"a@b.c","displayName":"A","extra":"ignored"}"""

    @Test fun `sends bearer and decodes ignoring unknown keys`() = runBlocking {
        server.enqueue(MockResponse().setBody(meJson))
        val me = api.get<Me>("me")
        assertEquals("A", me.displayName)
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `backend error becomes ApiException with message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"code":"X","message":"Không hợp lệ"}"""))
        try {
            api.get<Me>("me")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(422, e.status)
            assertEquals("X", e.code)
            assertEquals("Không hợp lệ", e.message)
        }
    }

    @Test fun `401 triggers one refresh then retries with new token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"accessToken":"new","refreshToken":"refresh2"}"""))
        server.enqueue(MockResponse().setBody(meJson))
        api.get<Me>("me")
        assertEquals("new", tokens.a)
        server.takeRequest(); server.takeRequest()
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `failed refresh clears session and notifies`() = runBlocking {
        var expired = false
        api.onSessionExpired = { expired = true }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.get<Me>("me") } catch (_: ApiException) {}
        assertTrue(expired)
        assertFalse(tokens.hasSession)
    }

    @Test fun `idempotency key sent only when requested`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        api.postUnit("a")
        api.post<Unit>("b", idempotent = true)
        assertEquals(null, server.takeRequest().getHeader("Idempotency-Key"))
        assertTrue(server.takeRequest().getHeader("Idempotency-Key")!!.length > 20)
    }
}
