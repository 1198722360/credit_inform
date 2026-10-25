package com.creditinform.app.network

import com.creditinform.app.data.RelayAlarmEvent
import com.creditinform.app.data.RelayAlertType
import com.creditinform.app.data.RelayClient
import com.creditinform.app.data.RelayConfig
import com.creditinform.app.data.RelayHealth
import com.creditinform.app.data.RelayPollResult
import com.creditinform.app.data.RelayRegistration
import com.creditinform.app.data.RelayServerStatus
import com.creditinform.app.data.RelaySendResult
import com.creditinform.app.data.RemoteMonitorConfigStatus
import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorConfigsResult
import com.creditinform.app.data.RemoteMonitorDeleteResult
import com.creditinform.app.data.RemoteMonitorMutationResult
import com.creditinform.app.data.RemoteMonitorRefreshCheckResult
import com.creditinform.app.data.RemoteMonitorRefreshError
import com.creditinform.app.data.RemoteMonitorRefreshItemResult
import com.creditinform.app.data.RemoteMonitorRefreshResult
import com.creditinform.app.data.RemoteMonitorStatus
import com.creditinform.app.data.RemoteMonitorTestResult
import com.creditinform.app.data.RemoteMonitorUpsertRequest
import com.creditinform.app.data.RemoteMonitorsResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class RelayApi {
    fun health(config: RelayConfig): RelayHealth {
        val json = execute(config, "GET", "/health", auth = Auth.NONE)
        require(json.optString("status") == "ok") { "服务端健康检查未返回 ok" }
        val clients = json.optJSONObject("clients") ?: JSONObject()
        return RelayHealth(
            service = json.optString("service"),
            version = json.optString("version"),
            serverInstanceId = json.nullableString("serverInstanceId"),
            serverTime = json.optLong("serverTime"),
            uptimeSeconds = json.optLong("uptimeSeconds"),
            onlineClients = clients.optInt("online"),
            totalClients = clients.optInt("total"),
            heartbeatTimeoutSeconds = json.optInt("heartbeatTimeoutSeconds")
        )
    }

    fun status(config: RelayConfig): RelayServerStatus {
        val json = execute(
            config = config,
            method = "GET",
            path = "/api/v1/status",
            query = emptyMap(),
            auth = Auth.SHARED
        )
        require(json.optString("status") == "ok") { "服务端组状态未返回 ok" }
        val clients = json.optJSONObject("clients") ?: JSONObject()
        return RelayServerStatus(
            serverInstanceId = json.nullableString("serverInstanceId"),
            serverTime = json.optLong("serverTime"),
            cursor = json.optLong("cursor"),
            onlineClients = clients.optInt("online"),
            totalClients = clients.optInt("total")
        )
    }

    fun register(config: RelayConfig): RelayRegistration =
        parseRegistration(
            execute(
                config = config,
                method = "POST",
                path = "/api/v1/clients/register",
                body = clientBody(config),
                auth = Auth.SHARED
            )
        )

    fun heartbeat(config: RelayConfig): RelayRegistration =
        parseRegistration(
            execute(
                config = config,
                method = "POST",
                path = "/api/v1/clients/heartbeat",
                body = clientBody(config),
                auth = Auth.SHARED
            )
        )

    fun sendAlarm(config: RelayConfig, message: String): RelaySendResult {
        val json = execute(
            config = config,
            method = "POST",
            path = "/api/v1/alerts",
            body = JSONObject().apply {
                put("senderId", config.deviceId)
                put("message", message)
            },
            auth = Auth.SHARED
        )
        require(json.optBoolean("accepted")) { "服务端未接受报警" }
        return RelaySendResult(
            event = json.requireObject("alert").toAlarmEvent(),
            recipients = json.optInt("recipients"),
            onlineRecipients = json.optInt("onlineRecipients"),
            serverTime = json.optLong("serverTime")
        )
    }

    fun poll(
        config: RelayConfig,
        after: Long,
        afterToken: String? = null,
        waitSeconds: Int = DEFAULT_POLL_SECONDS
    ): RelayPollResult {
        val wait = waitSeconds.coerceIn(0, MAX_POLL_SECONDS)
        val json = execute(
            config = config,
            method = "GET",
            path = "/api/v1/alerts",
            query = buildMap {
                put("clientId", config.deviceId)
                put("after", after.toString())
                afterToken?.takeIf { it.isNotBlank() }?.let { put("afterToken", it) }
                put("wait", wait.toString())
            },
            auth = Auth.SHARED,
            readTimeoutMs = wait * 1_000 + POLL_TIMEOUT_GRACE_MS
        )
        val alerts = json.optJSONArray("alerts") ?: JSONArray()
        val serverInstanceId = json.nullableString("serverInstanceId")
        return RelayPollResult(
            events = buildList {
                for (index in 0 until alerts.length()) {
                    add(alerts.getJSONObject(index).toAlarmEvent(serverInstanceId))
                }
            },
            cursor = json.optLong("cursor", after),
            headCursor = json.optLong("headCursor", json.optLong("cursor", after)),
            serverTime = json.optLong("serverTime"),
            serverInstanceId = serverInstanceId,
            cursorToken = json.nullableString("cursorToken"),
            cursorValid = json.optBoolean("cursorValid", true)
        )
    }

    fun monitors(config: RelayConfig): RemoteMonitorsResult {
        val json = execute(
            config = config,
            method = "GET",
            path = "/api/v1/monitors",
            query = emptyMap(),
            auth = Auth.SHARED
        )
        val configJson = json.optJSONObject("config") ?: JSONObject()
        val monitorJson = json.optJSONArray("monitors") ?: JSONArray()
        return RemoteMonitorsResult(
            serverTime = json.optLong("serverTime"),
            config = RemoteMonitorConfigStatus(
                loaded = configJson.optBoolean("loaded"),
                lastLoadedAt = configJson.optLong("lastLoadedAt"),
                lastError = configJson.optString("lastError")
            ),
            monitors = buildList {
                for (index in 0 until monitorJson.length()) {
                    add(monitorJson.getJSONObject(index).toRemoteMonitorStatus())
                }
            }
        )
    }

    fun listMonitorConfigs(config: RelayConfig): RemoteMonitorConfigsResult {
        val json = execute(
            config = config,
            method = "GET",
            path = "/api/v1/admin/monitors",
            query = emptyMap(),
            auth = Auth.ADMIN
        )
        return json.toRemoteMonitorConfigsResult()
    }

    fun createMonitor(
        config: RelayConfig,
        request: RemoteMonitorUpsertRequest
    ): RemoteMonitorMutationResult = parseMonitorMutation(
        execute(
            config = config,
            method = "POST",
            path = "/api/v1/admin/monitors",
            body = request.toJson(),
            auth = Auth.ADMIN
        )
    )

    fun updateMonitor(
        config: RelayConfig,
        monitorId: String,
        request: RemoteMonitorUpsertRequest
    ): RemoteMonitorMutationResult = parseMonitorMutation(
        execute(
            config = config,
            method = "PUT",
            path = "/api/v1/admin/monitors/${monitorId.urlEncode()}",
            body = request.toJson(),
            auth = Auth.ADMIN
        )
    )

    fun deleteMonitor(config: RelayConfig, monitorId: String): RemoteMonitorDeleteResult {
        val json = execute(
            config = config,
            method = "DELETE",
            path = "/api/v1/admin/monitors/${monitorId.urlEncode()}",
            query = emptyMap(),
            auth = Auth.ADMIN
        )
        require(json.optBoolean("ok")) { "服务端未确认删除监控" }
        return RemoteMonitorDeleteResult(serverTime = json.optLong("serverTime"))
    }

    fun copyMonitor(config: RelayConfig, monitorId: String): RemoteMonitorMutationResult =
        parseMonitorMutation(
            execute(
                config = config,
                method = "POST",
                path = "/api/v1/admin/monitors/${monitorId.urlEncode()}/copy",
                body = JSONObject(),
                auth = Auth.ADMIN
            )
        )

    fun reorderMonitors(
        config: RelayConfig,
        expectedMonitorIds: List<String>,
        monitorIds: List<String>
    ): RemoteMonitorConfigsResult = execute(
        config = config,
        method = "POST",
        path = "/api/v1/admin/monitors/reorder",
        body = JSONObject().apply {
            put("expectedMonitorIds", JSONArray(expectedMonitorIds))
            put("monitorIds", JSONArray(monitorIds))
        },
        auth = Auth.ADMIN
    ).toRemoteMonitorConfigsResult()

    fun testMonitor(config: RelayConfig, monitorId: String): RemoteMonitorTestResult {
        val json = execute(
            config = config,
            method = "POST",
            path = "/api/v1/admin/monitors/${monitorId.urlEncode()}/test",
            body = JSONObject(),
            auth = Auth.ADMIN,
            readTimeoutMs = MONITOR_TEST_READ_TIMEOUT_MS
        )
        if (!json.optBoolean("ok")) {
            val error = json.optJSONObject("error")
            val message = error?.optString("message").orEmpty()
                .ifBlank { "服务端测试监控失败" }
            throw RelayApiException(error?.nullableInt("httpCode") ?: 0, message)
        }
        val result = json.requireObject("result")
        return RemoteMonitorTestResult(
            serverTime = json.optLong("serverTime"),
            httpCode = result.optInt("httpCode"),
            rawValue = result.nullableDouble("rawValue"),
            value = result.nullableDouble("value"),
            breached = result.optBoolean("breached")
        )
    }

    fun refreshMonitors(config: RelayConfig): RemoteMonitorRefreshResult {
        val json = execute(
            config = config,
            method = "POST",
            path = "/api/v1/admin/monitors/refresh",
            body = JSONObject(),
            auth = Auth.ADMIN,
            readTimeoutMs = MONITOR_REFRESH_READ_TIMEOUT_MS
        )
        val resultJson = json.optJSONArray("results") ?: JSONArray()
        val results = buildList {
            for (index in 0 until resultJson.length()) {
                val item = resultJson.getJSONObject(index)
                val check = item.optJSONObject("result")
                val error = item.optJSONObject("error")
                add(
                    RemoteMonitorRefreshItemResult(
                        monitorId = item.optString("monitorId"),
                        monitorName = item.optString("monitorName"),
                        ok = item.optBoolean("ok"),
                        result = check?.let {
                            RemoteMonitorRefreshCheckResult(
                                httpCode = it.nullableInt("httpCode"),
                                rawValue = it.nullableDouble("rawValue"),
                                value = it.nullableDouble("value"),
                                breached = it.optBoolean("breached")
                            )
                        },
                        error = error?.let {
                            RemoteMonitorRefreshError(
                                code = it.optString("code"),
                                message = it.optString("message"),
                                httpCode = it.nullableInt("httpCode")
                            )
                        }
                    )
                )
            }
        }
        val succeeded = json.optInt("succeeded", results.count { it.ok })
        val total = json.optInt("total", results.size)
        return RemoteMonitorRefreshResult(
            ok = json.optBoolean("ok", succeeded == total),
            serverTime = json.optLong("serverTime"),
            total = total,
            succeeded = succeeded,
            failed = json.optInt("failed", (total - succeeded).coerceAtLeast(0)),
            results = results
        )
    }

    private fun execute(
        config: RelayConfig,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        auth: Auth,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): JSONObject {
        validateServerUrl(config.serverUrl)
        val authToken = when (auth) {
            Auth.NONE -> ""
            Auth.SHARED -> config.sharedToken.also {
                require(it.isNotBlank()) { "共享密钥不能为空" }
            }
            Auth.ADMIN -> config.adminToken.also {
                require(it.isNotBlank()) { "管理员密钥不能为空" }
            }
        }

        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        val requestUrl = buildString {
            append(config.serverUrl.trim().trimEnd('/'))
            append(path)
            if (queryString.isNotEmpty()) append('?').append(queryString)
        }
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            useCaches = false
            doInput = true
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (authToken.isNotEmpty()) setRequestProperty("Authorization", "Bearer $authToken")
        }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let(::readLimited).orEmpty()
            if (code !in 200..299) throw RelayApiException(code, parseError(response, code))
            require(response.isNotBlank()) { "服务端返回空响应" }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRegistration(json: JSONObject): RelayRegistration {
        require(json.optBoolean("ok")) { "客户端注册失败" }
        val client = json.requireObject("client")
        val clients = json.optJSONObject("clients") ?: JSONObject()
        return RelayRegistration(
            serverTime = json.optLong("serverTime"),
            cursor = json.optLong("cursor"),
            client = RelayClient(
                groupId = client.optString("groupId"),
                id = client.optString("id"),
                name = client.optString("name"),
                lastSeenAt = client.optLong("lastSeenAt")
            ),
            onlineClients = clients.optInt("online"),
            totalClients = clients.optInt("total"),
            serverInstanceId = json.nullableString("serverInstanceId"),
            cursorToken = json.nullableString("cursorToken")
        )
    }

    private fun parseMonitorMutation(json: JSONObject): RemoteMonitorMutationResult {
        require(json.optBoolean("ok")) { "服务端未确认监控配置更新" }
        return RemoteMonitorMutationResult(
            serverTime = json.optLong("serverTime"),
            keepPlaceholder = json.optString(
                "keepPlaceholder",
                RemoteMonitorConfig.KEEP_PLACEHOLDER
            ),
            monitor = json.requireObject("monitor").toRemoteMonitorConfig()
        )
    }

    private fun clientBody(config: RelayConfig) = JSONObject().apply {
        put("clientId", config.deviceId)
    }

    private fun JSONObject.toAlarmEvent(serverInstanceId: String? = null) = RelayAlarmEvent(
        eventId = getLong("id"),
        type = RelayAlertType.fromWire(optString("type", RelayAlertType.MANUAL.wireValue)),
        groupId = optString("groupId"),
        senderId = optString("senderId"),
        senderName = optString("senderName"),
        message = optString("message"),
        createdAt = optLong("createdAt"),
        monitorId = nullableString("monitorId"),
        monitorName = nullableString("monitorName"),
        eventUid = nullableString("eventUid"),
        serverInstanceId = serverInstanceId
    )

    private fun JSONObject.toRemoteMonitorStatus() = RemoteMonitorStatus(
        id = getString("id"),
        name = optString("name"),
        enabled = optBoolean("enabled", true),
        url = optString("url"),
        method = optString("method", "GET"),
        operator = optString("operator"),
        threshold = optDouble("threshold"),
        pollIntervalSeconds = optLong("pollIntervalSeconds"),
        lastCheckAt = optLong("lastCheckAt"),
        lastSuccessAt = optLong("lastSuccessAt"),
        lastHttpCode = nullableInt("lastHttpCode"),
        lastRawValue = nullableDouble("lastRawValue"),
        lastValue = nullableDouble("lastValue"),
        lastError = optString("lastError"),
        breached = optBoolean("breached"),
        consecutiveErrors = optInt("consecutiveErrors"),
        nextCheckAt = optLong("nextCheckAt")
    )

    private fun JSONObject.toRemoteMonitorConfig(): RemoteMonitorConfig {
        val headersJson = nullableJsonText("headersJson")
        val body = nullableJsonText("body")
        return RemoteMonitorConfig(
            groupId = optString("groupId"),
            id = getString("id"),
            revision = optLong("revision"),
            updatedAt = optLong("updatedAt"),
            name = optString("name"),
            url = optString("url"),
            method = optString("method", "GET"),
            headersJson = headersJson,
            body = body,
            urlMasked = optBoolean("urlMasked"),
            headersConfigured = optBoolean(
                "headersConfigured",
                headersJson?.let { runCatching { JSONObject(it).length() > 0 }.getOrDefault(true) }
                    ?: false
            ),
            bodyConfigured = optBoolean("bodyConfigured", body != null),
            jsonPath = optString("jsonPath"),
            operator = optString("operator", "<"),
            threshold = optDouble("threshold"),
            multiplier = optDouble("multiplier", 1.0),
            offset = optDouble("offset"),
            pollIntervalSeconds = optLong("pollIntervalSeconds", 60),
            timeoutSeconds = optInt("timeoutSeconds", 15),
            errorThreshold = optInt("errorThreshold", 3),
            repeatAlertSeconds = optLong("repeatAlertSeconds", 300),
            enabled = optBoolean("enabled", true),
            provider = optString("provider", "http"),
            gatewayAccessKey = optString("gatewayAccessKey"),
            gatewaySecretKeyEnv = optString("gatewaySecretKeyEnv", "GATEWAY_SK")
        )
    }

    private fun JSONObject.toRemoteMonitorConfigsResult(): RemoteMonitorConfigsResult {
        val monitorJson = optJSONArray("monitors") ?: JSONArray()
        return RemoteMonitorConfigsResult(
            serverTime = optLong("serverTime"),
            keepPlaceholder = optString(
                "keepPlaceholder",
                RemoteMonitorConfig.KEEP_PLACEHOLDER
            ),
            monitors = buildList {
                for (index in 0 until monitorJson.length()) {
                    add(monitorJson.getJSONObject(index).toRemoteMonitorConfig())
                }
            }
        )
    }

    private fun RemoteMonitorUpsertRequest.toJson() = JSONObject().apply {
        put("name", name)
        put("url", url)
        put("method", method)
        put("provider", provider)
        put("gatewayAccessKey", gatewayAccessKey)
        put("gatewaySecretKeyEnv", gatewaySecretKeyEnv)
        headersJson?.let { put("headersJson", it) }
        if (bodySpecified) put("body", body ?: JSONObject.NULL)
        put("jsonPath", jsonPath)
        put("operator", operator)
        put("threshold", threshold)
        put("multiplier", multiplier)
        put("offset", offset)
        put("pollIntervalSeconds", pollIntervalSeconds)
        put("timeoutSeconds", timeoutSeconds)
        put("errorThreshold", errorThreshold)
        put("repeatAlertSeconds", repeatAlertSeconds)
        put("enabled", enabled)
        revision?.let { put("revision", it) }
    }

    private fun JSONObject.requireObject(key: String): JSONObject =
        optJSONObject(key) ?: error("服务端响应缺少 $key")

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableJsonText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString()
        }
    }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)

    private fun validateServerUrl(value: String) {
        require(value.trim().length in 1..MAX_SERVER_URL_CHARS) {
            "服务端地址不能超过 $MAX_SERVER_URL_CHARS 个字符"
        }
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(uri != null && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
            "服务端地址必须是有效的 http:// 或 https:// 地址"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) { "服务端地址不能包含查询参数或片段" }
    }

    private fun parseError(response: String, code: Int): String = runCatching {
        JSONObject(response).optJSONObject("error")?.optString("message")
    }.getOrNull().orEmpty().ifBlank {
        "服务端返回 HTTP $code${response.take(ERROR_PREVIEW_CHARS).let { if (it.isBlank()) "" else ": $it" }}"
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun readLimited(stream: InputStream): String =
        stream.reader(Charsets.UTF_8).buffered().use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(8_192)
            while (output.length < MAX_RESPONSE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - output.length))
                if (count < 0) break
                output.append(buffer, 0, count)
            }
            output.toString()
        }

    companion object {
        const val DEFAULT_POLL_SECONDS = 25
        private const val MAX_POLL_SECONDS = 30
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000
        private const val MONITOR_TEST_READ_TIMEOUT_MS = 45_000
        private const val MONITOR_REFRESH_READ_TIMEOUT_MS = 120_000
        private const val POLL_TIMEOUT_GRACE_MS = 10_000
        private const val MAX_RESPONSE_CHARS = 1_048_576
        private const val MAX_SERVER_URL_CHARS = 2_048
        private const val ERROR_PREVIEW_CHARS = 500
    }

    private enum class Auth {
        NONE,
        SHARED,
        ADMIN
    }
}

class RelayApiException(
    val httpCode: Int,
    override val message: String
) : Exception(message)
