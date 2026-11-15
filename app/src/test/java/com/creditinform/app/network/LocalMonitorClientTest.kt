package com.creditinform.app.network

import com.creditinform.app.data.RemoteMonitorConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

internal fun testMonitor(url: String = "https://example.test/balance") = RemoteMonitorConfig(
    groupId = "local", id = "balance", revision = 1, updatedAt = 0, name = "Balance",
    url = url, method = "GET", headersJson = "{\"Authorization\":\"Bearer local-secret\"}", body = null,
    jsonPath = "data.balance", operator = "<", threshold = 5.0, multiplier = 1.0, offset = 0.0,
    pollIntervalSeconds = 60, timeoutSeconds = 1, errorThreshold = 3, repeatAlertSeconds = 300, enabled = true)

class LocalMonitorClientTest {
    @Test fun `phone requests only configured endpoint and evaluates converted balance`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"data\":{\"balance\":\"300\"}}"))
            val monitor = testMonitor(server.url("/business/balance?account=1").toString()).copy(multiplier = 0.01, offset = 1.0)
            val result = LocalMonitorClient().check(monitor)
            assertEquals(300.0, result.rawValue!!, 0.0)
            assertEquals(4.0, result.value!!, 0.0)
            assertTrue(result.breached)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/business/balance?account=1", request.path)
            assertEquals("Bearer local-secret", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `post sends configured body directly`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"data\":{\"balance\":6}}"))
            val result = LocalMonitorClient().check(testMonitor(server.url("/balance").toString()).copy(method = "POST", body = "{\"account\":7}"))
            assertFalse(result.breached)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("{\"account\":7}", request.body.readUtf8())
        }
    }

    @Test fun `redirect cannot forward credentials to another host`() {
        MockWebServer().use { server -> MockWebServer().use { target ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/stolen")))
            val error = runCatching { LocalMonitorClient().check(testMonitor(server.url("/balance").toString())) }.exceptionOrNull()
            assertEquals(302, (error as RelayApiException).httpCode)
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun `upstream error never exposes response secrets`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret-token=abc"))
            val error = runCatching { LocalMonitorClient().check(testMonitor(server.url("/balance?secret=abc").toString())) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("abc"))
            assertEquals(403, (error as RelayApiException).httpCode)
        }
    }

    @Test fun `bad and oversized responses fail instead of generating a balance`() {
        MockWebServer().use { server ->
            for (body in listOf("not json", "{\"data\":{\"balance\":null}}", "x".repeat(1048577))) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(runCatching { LocalMonitorClient().check(testMonitor(server.url("/balance").toString())) }.exceptionOrNull() is RelayApiException)
            }
        }
    }

    @Test fun `json path supports nested arrays and numeric strings but rejects invalid values`() {
        val json = JSONObject("{\"data\":[{\"balance\":\"12.5\"}],\"flag\":true,\"bad\":\"NaN\"}")
        assertEquals(12.5, LocalMonitorClient.readNumber(json, "$.data[0].balance"), 0.0)
        for (path in listOf("data[0]..balance", "data[0].balance.", "missing", "flag", "bad")) {
            assertTrue(path, runCatching { LocalMonitorClient.readNumber(json, path) }.isFailure)
        }
    }

    @Test fun `all operators respect equality boundary`() {
        for (op in listOf("<=", ">=", "=", "==")) assertTrue(LocalMonitorClient.breached(5.0, op, 5.0))
        for (op in listOf("<", ">")) assertFalse(LocalMonitorClient.breached(5.0, op, 5.0))
    }

    @Test fun `gateway matches published request and response vectors and rejects tampering`() {
        val vector = JSONObject(javaClass.getResource("/gateway-test-vectors.json")!!.readText())
        val (requestKey, responseKey) = GatewayCrypto.deriveKeys(vector.getString("access_key"), vector.getString("secret_key"))
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(vector.getJSONObject("derive").getJSONObject("req_key").getString("hex"), requestKey.hex())
        assertEquals(vector.getJSONObject("derive").getJSONObject("resp_key").getString("hex"), responseKey.hex())
        val req = vector.getJSONObject("request")
        val resp = vector.getJSONObject("response")
        assertEquals(req.getString("payload"), GatewayCrypto.seal(requestKey, req.getString("plaintext"), req.getString("aad"), req.getString("iv_hex").bytes()))
        assertEquals(resp.getString("plaintext"), GatewayCrypto.open(responseKey, resp.getString("payload"), resp.getString("aad")))
        assertTrue(runCatching { GatewayCrypto.open(responseKey, resp.getString("payload"), resp.getString("aad") + "x") }.isFailure)
        assertTrue(runCatching { GatewayCrypto.open(requestKey, resp.getString("payload"), resp.getString("aad")) }.isFailure)
    }
}
