package com.creditinform.app.network

import com.creditinform.app.data.RelayConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerBalanceClientTest {
    private fun response(status: String = "low", stale: Boolean = false) = """
        {"code":0,"data":{"generated_at":"2026-11-16T01:00:00Z","interval_seconds":60,
        "error_threshold":4,"repeat_alert_seconds":600,"monitors":[{"id":"source:2","name":"Balance",
        "enabled":true,"threshold":10,"balance":3,"status":"$status","unit":"USD",
        "checked_at":"2026-11-16T00:59:30Z","stale":$stale,
        "api_key":"must-not-store","headers":{"Authorization":"secret"},"error":"upstream-secret"}]}}
    """.trimIndent()

    @Test fun `fetch uses only read token and discards upstream fields`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response()))
            val result = ServerBalanceClient().fetch(RelayConfig(serverUrl = server.url("/").toString(), apiToken = "read-only"))
            val request = server.takeRequest()
            assertEquals(ServerBalanceClient.API_PATH, request.path)
            assertEquals("Bearer read-only", request.getHeader("Authorization"))
            val item = result.monitors.single()
            assertEquals(3.0, item.result.getOrThrow().value!!, 0.0)
            assertTrue(item.result.getOrThrow().breached)
            assertEquals(4, item.config.errorThreshold)
            assertEquals(600, item.config.repeatAlertSeconds)
            assertEquals("", item.config.url)
            assertNull(item.config.headersJson)
            assertEquals("", item.config.gatewaySecretKey)
            assertFalse(item.toString().contains("must-not-store"))
        }
    }

    @Test fun `redirect never forwards read credentials`() {
        MockWebServer().use { server -> MockWebServer().use { target ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/capture")))
            val error = runCatching { ServerBalanceClient().fetch(RelayConfig(serverUrl = server.url("/").toString(), apiToken = "read-only")) }.exceptionOrNull()
            assertEquals(302, (error as RelayApiException).httpCode)
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun `rejects public cleartext and embedded credentials`() {
        listOf("http://balance.example", "https://user:password@balance.example", "https://balance.example?token=secret", "https://balance.example/#secret").forEach {
            assertTrue(runCatching { ServerBalanceClient.validateServerUrl(it) }.isFailure)
        }
        assertNotNull(ServerBalanceClient.validateServerUrl("https://balance.example/sub2api"))
    }

    @Test fun `stale result cannot be treated as healthy balance`() {
        val item = ServerBalanceClient.parse(JSONObject(response(status = "ok", stale = true))).monitors.single()
        assertTrue(item.result.isFailure)
        assertEquals(1794790800000L, item.checkedAt)
        assertFalse(item.result.exceptionOrNull()!!.message!!.contains("upstream-secret"))
    }

    @Test fun `unlimited is distinct from zero and pending is a failure`() {
        val unlimited = ServerBalanceClient.parse(JSONObject(response("unlimited"))).monitors.single().result.getOrThrow()
        assertFalse(unlimited.breached)
        assertNull(unlimited.value)
        assertTrue(ServerBalanceClient.parse(JSONObject(response("pending"))).monitors.single().result.isFailure)
    }

    @Test fun `duplicate ids and malformed balance reject the entire snapshot`() {
        val duplicate = JSONObject(response()).apply {
            getJSONObject("data").getJSONArray("monitors").put(getJSONObject("data").getJSONArray("monitors").getJSONObject(0))
        }
        assertTrue(runCatching { ServerBalanceClient.parse(duplicate) }.isFailure)
        val malformed = JSONObject(response()).apply { getJSONObject("data").getJSONArray("monitors").getJSONObject(0).put("balance", "secret-value") }
        assertTrue(runCatching { ServerBalanceClient.parse(malformed) }.isFailure)
    }

    @Test fun `server error content never reaches alerts`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("api-key-secret"))
            val error = runCatching { ServerBalanceClient().fetch(RelayConfig(serverUrl = server.url("/").toString(), apiToken = "read-only")) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("api-key-secret"))
            assertEquals(401, (error as RelayApiException).httpCode)
        }
    }
}
