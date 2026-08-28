package com.creditinform.app.network

import com.creditinform.app.data.RelayAlertType
import com.creditinform.app.data.RelayConfig
import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorUpsertRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RelayApiTest {
    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    @Volatile
    private var serverFailure: Throwable? = null
    private val requests = CopyOnWriteArrayList<CapturedRequest>()

    @Before
    fun setUp() {
        serverFailure = null
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        serverThread = thread(name = "relay-api-test-server", isDaemon = true) {
            try {
                while (!serverSocket.isClosed) {
                    serverSocket.accept().use(::handle)
                }
            } catch (error: SocketException) {
                if (!serverSocket.isClosed) serverFailure = error
            } catch (error: Throwable) {
                serverFailure = error
            }
        }
    }

    @After
    fun tearDown() {
        serverSocket.close()
        serverThread.join(2_000)
        serverFailure?.let { throw AssertionError("Local HTTP fixture failed", it) }
    }

    @Test
    fun `uses separate auth tokens and parses relay and monitor contracts`() {
        val api = RelayApi()
        val config = RelayConfig(
            serverUrl = "http://127.0.0.1:${serverSocket.localPort}",
            groupId = "night-team",
            sharedToken = "shared-secret",
            adminToken = "admin-secret",
            deviceId = "owner-phone",
            deviceName = "Owner",
            enabled = true
        )

        val registration = api.register(config)
        assertEquals(7L, registration.cursor)
        assertEquals(2, registration.onlineClients)
        assertEquals("instance-primary", registration.serverInstanceId)

        val status = api.status(config)
        assertEquals("instance-primary", status.serverInstanceId)
        assertEquals(8L, status.cursor)
        assertEquals(2, status.onlineClients)
        assertEquals(3, status.totalClients)

        val poll = api.poll(config, after = 7, waitSeconds = 0)
        assertEquals(8L, poll.cursor)
        assertEquals(8L, poll.headCursor)
        assertEquals("instance-primary", poll.serverInstanceId)
        assertEquals(RelayAlertType.MONITOR_BREACH, poll.events.single().type)
        assertEquals("balance", poll.events.single().monitorId)
        assertEquals("instance-primary", poll.events.single().serverInstanceId)
        assertEquals("uid:00000000000000000000000000000008", poll.events.single().acknowledgementKey)

        val sent = api.sendAlarm(config, "Please investigate")
        assertEquals(3, sent.recipients)
        assertEquals(1, sent.onlineRecipients)

        val runtime = api.monitors(config)
        assertTrue(runtime.config.loaded)
        assertEquals(12.5, runtime.monitors.single().lastValue!!, 0.000_001)

        val configs = api.listMonitorConfigs(config)
        assertEquals(RemoteMonitorConfig.KEEP_PLACEHOLDER, configs.keepPlaceholder)
        assertEquals(3L, configs.monitors.single().revision)
        assertEquals("{\"Authorization\":\"__KEEP__\"}", configs.monitors.single().headersJson)
        assertTrue(configs.monitors.single().urlMasked)
        assertTrue(configs.monitors.single().headersConfigured)
        assertTrue(configs.monitors.single().bodyConfigured)

        val request = RemoteMonitorUpsertRequest(
            name = "Balance",
            url = "https://business.example/api?token=__KEEP__",
            headersJson = "{\"Authorization\":\"__KEEP__\"}",
            body = RemoteMonitorConfig.KEEP_PLACEHOLDER,
            jsonPath = "data.balance",
            threshold = 10.0,
            revision = 3
        )
        assertEquals(4L, api.updateMonitor(config, "balance id", request).monitor.revision)
        api.updateMonitor(
            config,
            "clear body",
            request.copy(body = null, bodySpecified = true)
        )
        api.updateMonitor(
            config,
            "preserve body",
            request.copy(body = null, bodySpecified = false)
        )
        assertEquals(200, api.testMonitor(config, "balance id").httpCode)
        assertEquals(99L, api.deleteMonitor(config, "balance id").serverTime)

        val clearBodyJson = capturedJsonBody("/api/v1/admin/monitors/clear%20body")
        assertTrue(clearBodyJson.has("body"))
        assertTrue(clearBodyJson.isNull("body"))
        val preserveBodyJson = capturedJsonBody("/api/v1/admin/monitors/preserve%20body")
        assertFalse(preserveBodyJson.has("body"))

        requests.filter { it.path.startsWith("/api/v1/admin/") }.forEach {
            assertEquals("Bearer admin-secret", it.authorization)
        }
        requests.filter {
            it.path == "/api/v1/clients/register" ||
                it.path == "/api/v1/status" ||
                it.path == "/api/v1/alerts" ||
                it.path == "/api/v1/monitors"
        }.forEach {
            assertEquals("Bearer shared-secret", it.authorization)
        }
        assertTrue(requests.any { it.path.contains("balance%20id") })
    }

    @Test
    fun `accepts legacy status registration and poll responses without server instance`() {
        val api = RelayApi()
        val config = RelayConfig(
            serverUrl = "http://127.0.0.1:${serverSocket.localPort}",
            groupId = "legacy-team",
            sharedToken = "legacy-secret",
            deviceId = "legacy-phone",
            deviceName = "Legacy phone"
        )

        val status = api.status(config)
        val registration = api.register(config)
        val poll = api.poll(config, after = 3, waitSeconds = 0)

        assertNull(status.serverInstanceId)
        assertNull(registration.serverInstanceId)
        assertNull(poll.serverInstanceId)
        assertNull(poll.events.single().serverInstanceId)
        assertEquals("4", poll.events.single().acknowledgementKey)
        requests.forEach { assertEquals("Bearer legacy-secret", it.authorization) }
    }

    @Test
    fun `surfaces monitor test failure returned with http 200`() {
        val api = RelayApi()
        val config = RelayConfig(
            serverUrl = "http://127.0.0.1:${serverSocket.localPort}",
            groupId = "night-team",
            adminToken = "admin-secret"
        )

        val error = runCatching { api.testMonitor(config, "broken") }.exceptionOrNull()

        assertTrue(error is RelayApiException)
        assertEquals(503, (error as RelayApiException).httpCode)
        assertEquals("上游余额接口暂时不可用", error.message)
    }

    @Test
    fun `surfaces structured server errors`() {
        val api = RelayApi()
        val config = RelayConfig(
            serverUrl = "http://127.0.0.1:${serverSocket.localPort}",
            groupId = "forbidden",
            adminToken = "wrong"
        )

        val error = runCatching { api.listMonitorConfigs(config) }.exceptionOrNull()
        assertTrue(error is RelayApiException)
        assertEquals(401, (error as RelayApiException).httpCode)
        assertEquals("管理员密钥错误", error.message)
    }

    private fun handle(socket: Socket) {
        val request = readRequest(socket.getInputStream())
        val uri = URI(request.target)
        requests += CapturedRequest(
            method = request.method,
            path = uri.rawPath,
            query = uri.rawQuery.orEmpty(),
            authorization = request.headers["authorization"].orEmpty(),
            body = request.body
        )

        if (uri.rawQuery?.contains("groupId=forbidden") == true) {
            respond(socket.getOutputStream(), 401, "{\"error\":{\"code\":\"unauthorized\",\"message\":\"管理员密钥错误\"}}")
            return
        }

        val response = when {
            uri.path == "/api/v1/status" && uri.rawQuery?.contains("groupId=legacy-team") == true ->
                """{"status":"ok","serverTime":99,"cursor":3,"clients":{"online":1,"total":1}}"""
            uri.path == "/api/v1/status" ->
                """{"status":"ok","serverInstanceId":"instance-primary","serverTime":99,"cursor":8,"clients":{"online":2,"total":3}}"""
            uri.path == "/api/v1/clients/register" && JSONObject(request.body).optString("groupId") == "legacy-team" ->
                """{"ok":true,"serverTime":99,"cursor":3,"clients":{"online":1,"total":1},"client":{"groupId":"legacy-team","id":"legacy-phone","name":"Legacy phone","lastSeenAt":99}}"""
            uri.path == "/api/v1/clients/register" ->
                """{"ok":true,"serverInstanceId":"instance-primary","serverTime":99,"cursor":7,"clients":{"online":2,"total":3},"client":{"groupId":"night-team","id":"owner-phone","name":"Owner","lastSeenAt":99}}"""
            uri.path == "/api/v1/alerts" && request.method == "GET" && uri.rawQuery?.contains("groupId=legacy-team") == true ->
                """{"serverTime":99,"cursor":4,"headCursor":4,"alerts":[{"id":4,"type":"manual","groupId":"legacy-team","senderId":"support","senderName":"Support","message":"Legacy alert","createdAt":98}]}"""
            uri.path == "/api/v1/alerts" && request.method == "GET" ->
                """{"serverInstanceId":"instance-primary","serverTime":99,"cursor":8,"headCursor":8,"alerts":[{"id":8,"eventUid":"00000000000000000000000000000008","type":"monitor_breach","groupId":"night-team","senderId":"server","senderName":"Server","message":"Low balance","monitorId":"balance","monitorName":"Balance","createdAt":98}]}"""
            uri.path == "/api/v1/alerts" && request.method == "POST" ->
                """{"accepted":true,"serverTime":99,"recipients":3,"onlineRecipients":1,"alert":{"id":9,"type":"manual","groupId":"night-team","senderId":"owner-phone","senderName":"Owner","message":"Please investigate","createdAt":99}}"""
            uri.path == "/api/v1/monitors" ->
                """{"serverTime":99,"config":{"loaded":true,"lastLoadedAt":90,"lastError":""},"monitors":[{"id":"balance","name":"Balance","enabled":true,"operator":"<","threshold":10,"pollIntervalSeconds":60,"lastCheckAt":98,"lastSuccessAt":98,"lastHttpCode":200,"lastRawValue":25,"lastValue":12.5,"lastError":"","breached":false,"consecutiveErrors":0,"nextCheckAt":158}]}"""
            uri.path == "/api/v1/admin/monitors" && request.method == "GET" ->
                """{"serverTime":99,"keepPlaceholder":"__KEEP__","monitors":[${monitorJson(3)}]}"""
            uri.path == "/api/v1/admin/monitors/broken/test" ->
                """{"ok":false,"serverTime":99,"error":{"message":"上游余额接口暂时不可用","httpCode":503}}"""
            uri.path.startsWith("/api/v1/admin/monitors/") && uri.path.endsWith("/test") ->
                """{"ok":true,"serverTime":99,"result":{"httpCode":200,"rawValue":25,"value":12.5,"breached":false}}"""
            uri.path.startsWith("/api/v1/admin/monitors/") && request.method == "PUT" ->
                """{"ok":true,"serverTime":99,"keepPlaceholder":"__KEEP__","monitor":${monitorJson(4)}}"""
            uri.path.startsWith("/api/v1/admin/monitors/") && request.method == "DELETE" ->
                """{"ok":true,"serverTime":99}"""
            else -> """{"status":"ok","serverTime":99}"""
        }
        respond(socket.getOutputStream(), 200, response)
    }

    private fun monitorJson(revision: Int): String = JSONObject().apply {
        put("groupId", "night-team")
        put("id", "balance id")
        put("revision", revision)
        put("updatedAt", 99)
        put("name", "Balance")
        put("url", "https://business.example/api?token=__KEEP__")
        put("method", "GET")
        put("headersJson", "{\"Authorization\":\"__KEEP__\"}")
        put("body", "__KEEP__")
        put("urlMasked", true)
        put("headersConfigured", true)
        put("bodyConfigured", true)
        put("jsonPath", "data.balance")
        put("operator", "<")
        put("threshold", 10)
        put("multiplier", 1)
        put("offset", 0)
        put("pollIntervalSeconds", 60)
        put("timeoutSeconds", 15)
        put("errorThreshold", 3)
        put("repeatAlertSeconds", 300)
        put("enabled", true)
    }.toString()

    private fun capturedJsonBody(path: String): JSONObject {
        val request = requests.single { it.path == path && it.method == "PUT" }
        return JSONObject(request.body)
    }

    private fun readRequest(input: InputStream): TestRequest {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (matched < HEADER_TERMINATOR.size) {
            val next = input.read()
            require(next >= 0) { "Unexpected end of HTTP headers" }
            headerBytes.write(next)
            matched = if (next.toByte() == HEADER_TERMINATOR[matched]) matched + 1 else 0
            require(headerBytes.size() <= MAX_HEADER_BYTES) { "HTTP request headers are too large" }
        }

        val lines = headerBytes.toString(Charsets.ISO_8859_1.name())
            .removeSuffix("\r\n\r\n")
            .split("\r\n")
        val requestLine = lines.first().split(' ', limit = 3)
        require(requestLine.size == 3) { "Malformed HTTP request line" }
        val headers = lines.drop(1).associate { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "Malformed HTTP header" }
            line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength in 0..MAX_BODY_BYTES) { "HTTP request body is too large" }
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(bodyBytes, offset, contentLength - offset)
            require(count >= 0) { "Unexpected end of HTTP body" }
            offset += count
        }
        return TestRequest(
            method = requestLine[0],
            target = requestLine[1],
            headers = headers,
            body = bodyBytes.toString(Charsets.UTF_8)
        )
    }

    private fun respond(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (code == 200) "OK" else "Unauthorized"
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private data class TestRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val query: String,
        val authorization: String,
        val body: String
    )

    private companion object {
        val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
