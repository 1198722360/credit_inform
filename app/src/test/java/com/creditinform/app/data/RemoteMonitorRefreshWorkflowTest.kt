package com.creditinform.app.data

import com.creditinform.app.network.RelayApi
import com.creditinform.app.network.RelayApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteMonitorRefreshWorkflowTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `batch refresh uses admin auth then publishes latest shared snapshot`() {
        server.enqueue(jsonResponse(PARTIAL_REFRESH_RESPONSE))
        server.enqueue(jsonResponse(monitorsResponse(lastValue = 42.5, lastCheckAt = 500)))
        val statuses = MutableStateFlow<List<RemoteMonitorStatus>>(emptyList())
        val configStatus = MutableStateFlow(RemoteMonitorConfigStatus(false, 0, ""))

        val result = refreshRemoteMonitorValuesAndPublish(RelayApi(), relayConfig()) { latest ->
            statuses.value = latest.monitors
            configStatus.value = latest.config
            RemoteMonitorSnapshotPublication.PUBLISHED
        }

        assertFalse(result.ok)
        assertEquals(2, result.total)
        assertEquals(1, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(200, result.results.first().result?.httpCode)
        assertEquals("upstream_timeout", result.results.last().error?.code)
        assertEquals("余额接口超时", result.results.last().error?.message)
        assertNull(result.results.last().error?.httpCode)
        assertEquals(42.5, statuses.value.single().lastValue!!, 0.000_001)
        assertEquals(500L, statuses.value.single().lastCheckAt)
        assertTrue(configStatus.value.loaded)
        assertEquals(499L, configStatus.value.lastLoadedAt)

        val refreshRequest = server.takeRequest()
        assertEquals("POST", refreshRequest.method)
        assertEquals("/api/v1/admin/monitors/refresh", refreshRequest.path)
        assertEquals("Bearer admin-secret", refreshRequest.getHeader("Authorization"))
        assertEquals(0, JSONObject(refreshRequest.body.readUtf8()).length())

        val statusRequest = server.takeRequest()
        assertEquals("GET", statusRequest.method)
        assertEquals("/api/v1/monitors", statusRequest.path)
        assertEquals("Bearer shared-secret", statusRequest.getHeader("Authorization"))
        assertFalse(statusRequest.path.orEmpty().contains("groupId"))
    }

    @Test
    fun `admin failure does not fetch or replace monitor snapshot`() {
        server.enqueue(
            jsonResponse(
                body = """{"error":{"message":"管理员密钥错误"}}""",
                code = 401
            )
        )
        val original = monitorStatus(lastValue = 7.0, lastCheckAt = 100)
        val statuses = MutableStateFlow(listOf(original))

        val error = runCatching {
            refreshRemoteMonitorValuesAndPublish(RelayApi(), relayConfig()) { latest ->
                statuses.value = latest.monitors
                RemoteMonitorSnapshotPublication.PUBLISHED
            }
        }.exceptionOrNull()

        assertTrue(error is RelayApiException)
        assertEquals(401, (error as RelayApiException).httpCode)
        assertEquals("管理员密钥错误", error.message)
        assertEquals(listOf(original), statuses.value)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer admin-secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `snapshot failure after batch refresh leaves previous values intact`() {
        server.enqueue(jsonResponse(PARTIAL_REFRESH_RESPONSE))
        server.enqueue(
            jsonResponse(
                body = """{"error":{"message":"最新监控状态读取失败"}}""",
                code = 503
            )
        )
        val original = monitorStatus(lastValue = 7.0, lastCheckAt = 100)
        val statuses = MutableStateFlow(listOf(original))

        val error = runCatching {
            refreshRemoteMonitorValuesAndPublish(RelayApi(), relayConfig()) { latest ->
                statuses.value = latest.monitors
                RemoteMonitorSnapshotPublication.PUBLISHED
            }
        }.exceptionOrNull()

        assertTrue(error is RelayApiException)
        assertEquals(503, (error as RelayApiException).httpCode)
        assertEquals("最新监控状态读取失败", error.message)
        assertEquals(listOf(original), statuses.value)
        assertEquals(2, server.requestCount)
        assertEquals("Bearer admin-secret", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer shared-secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `older normal refresh arriving after batch snapshot cannot replace it`() {
        val gate = RemoteMonitorSnapshotGate()
        var publishedValue = 0.0

        val newer = gate.publish(
            serverTime = 200,
            relayIdentityMatches = true,
            adminIdentityMatches = true,
            requireAdminIdentity = false
        ) { publishedValue = 20.0 }
        val older = gate.publish(
            serverTime = 100,
            relayIdentityMatches = true,
            adminIdentityMatches = true,
            requireAdminIdentity = false
        ) { publishedValue = 10.0 }

        assertEquals(RemoteMonitorSnapshotPublication.PUBLISHED, newer)
        assertEquals(RemoteMonitorSnapshotPublication.IGNORED_STALE, older)
        assertEquals(20.0, publishedValue, 0.0)
    }

    @Test
    fun `equal and zero server times follow nondecreasing publication policy`() {
        val gate = RemoteMonitorSnapshotGate()
        var publishedValue = 0.0

        val firstZero = gate.publish(0, true, true, false) { publishedValue = 1.0 }
        val secondZero = gate.publish(0, true, true, false) { publishedValue = 2.0 }
        val positive = gate.publish(100, true, true, false) { publishedValue = 3.0 }
        val equal = gate.publish(100, true, true, false) { publishedValue = 4.0 }
        val lateZero = gate.publish(0, true, true, false) { publishedValue = 5.0 }

        assertEquals(RemoteMonitorSnapshotPublication.PUBLISHED, firstZero)
        assertEquals(RemoteMonitorSnapshotPublication.PUBLISHED, secondZero)
        assertEquals(RemoteMonitorSnapshotPublication.PUBLISHED, positive)
        assertEquals(RemoteMonitorSnapshotPublication.PUBLISHED, equal)
        assertEquals(RemoteMonitorSnapshotPublication.IGNORED_STALE, lateZero)
        assertEquals(4.0, publishedValue, 0.0)
    }

    @Test
    fun `configuration identity switch still rejects in-flight snapshot`() {
        val gate = RemoteMonitorSnapshotGate()
        var publishCount = 0

        gate.publish(
            serverTime = 100,
            relayIdentityMatches = true,
            adminIdentityMatches = true,
            requireAdminIdentity = true
        ) { publishCount++ }
        val relayIdentityChanged = gate.publish(
            serverTime = 200,
            relayIdentityMatches = false,
            adminIdentityMatches = true,
            requireAdminIdentity = false
        ) { publishCount++ }
        val adminIdentityChanged = gate.publish(
            serverTime = 200,
            relayIdentityMatches = true,
            adminIdentityMatches = false,
            requireAdminIdentity = true
        ) { publishCount++ }

        assertEquals(
            RemoteMonitorSnapshotPublication.REJECTED_IDENTITY,
            relayIdentityChanged
        )
        assertEquals(
            RemoteMonitorSnapshotPublication.REJECTED_IDENTITY,
            adminIdentityChanged
        )
        assertEquals(1, publishCount)
    }

    @Test
    fun `batch workflow cancels when snapshot belongs to previous configuration`() {
        server.enqueue(jsonResponse(PARTIAL_REFRESH_RESPONSE))
        server.enqueue(jsonResponse(monitorsResponse(lastValue = 42.5, lastCheckAt = 500)))
        val gate = RemoteMonitorSnapshotGate()
        var published = false

        val error = runCatching {
            refreshRemoteMonitorValuesAndPublish(RelayApi(), relayConfig()) { latest ->
                gate.publish(
                    serverTime = latest.serverTime,
                    relayIdentityMatches = false,
                    adminIdentityMatches = true,
                    requireAdminIdentity = true
                ) { published = true }
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertFalse(published)
    }

    @Test
    fun `identity reset accepts new server with earlier clock`() {
        val gate = RemoteMonitorSnapshotGate()
        var publishedValue = 0.0

        gate.publish(
            serverTime = 200,
            relayIdentityMatches = true,
            adminIdentityMatches = true,
            requireAdminIdentity = false
        ) { publishedValue = 20.0 }
        gate.reset()
        val afterReset = gate.publish(
            serverTime = 100,
            relayIdentityMatches = true,
            adminIdentityMatches = true,
            requireAdminIdentity = false
        ) { publishedValue = 10.0 }

        assertEquals(RemoteMonitorSnapshotPublication.PUBLISHED, afterReset)
        assertEquals(10.0, publishedValue, 0.0)
    }

    private fun relayConfig() = RelayConfig(
        serverUrl = server.url("/").toString().removeSuffix("/"),
        sharedToken = "shared-secret",
        adminToken = "admin-secret",
        deviceId = "test-client"
    )

    private fun monitorStatus(lastValue: Double, lastCheckAt: Long) = RemoteMonitorStatus(
        id = "balance",
        name = "Balance",
        enabled = true,
        url = "https://business.example/balance?token=***",
        method = "GET",
        operator = "<",
        threshold = 10.0,
        pollIntervalSeconds = 60,
        lastCheckAt = lastCheckAt,
        lastSuccessAt = lastCheckAt,
        lastHttpCode = 200,
        lastRawValue = lastValue,
        lastValue = lastValue,
        lastError = "",
        breached = false,
        consecutiveErrors = 0,
        nextCheckAt = lastCheckAt + 60
    )

    private fun monitorsResponse(lastValue: Double, lastCheckAt: Long): String =
        JSONObject().apply {
            put("serverTime", 501)
            put(
                "config",
                JSONObject().apply {
                    put("loaded", true)
                    put("lastLoadedAt", 499)
                    put("lastError", "")
                }
            )
            put(
                "monitors",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("id", "balance")
                        put("name", "Balance")
                        put("enabled", true)
                        put("url", "https://business.example/balance?token=***")
                        put("method", "GET")
                        put("operator", "<")
                        put("threshold", 10)
                        put("pollIntervalSeconds", 60)
                        put("lastCheckAt", lastCheckAt)
                        put("lastSuccessAt", lastCheckAt)
                        put("lastHttpCode", 200)
                        put("lastRawValue", lastValue)
                        put("lastValue", lastValue)
                        put("lastError", "")
                        put("breached", false)
                        put("consecutiveErrors", 0)
                        put("nextCheckAt", lastCheckAt + 60)
                    }
                )
            )
        }.toString()

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private companion object {
        const val PARTIAL_REFRESH_RESPONSE = """
            {
              "ok": false,
              "serverTime": 501,
              "total": 2,
              "succeeded": 1,
              "failed": 1,
              "results": [
                {
                  "monitorId": "balance",
                  "monitorName": "Balance",
                  "ok": true,
                  "result": {
                    "httpCode": 200,
                    "rawValue": 42.5,
                    "value": 42.5,
                    "breached": false
                  }
                },
                {
                  "monitorId": "broken",
                  "monitorName": "Broken endpoint",
                  "ok": false,
                  "error": {
                    "code": "upstream_timeout",
                    "message": "余额接口超时",
                    "httpCode": null
                  }
                }
              ]
            }
        """
    }
}
