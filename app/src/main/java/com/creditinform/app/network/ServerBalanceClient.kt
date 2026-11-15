package com.creditinform.app.network

import com.creditinform.app.data.RelayConfig
import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorTestResult
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

/** Fetches only sanitized balance snapshots; it never contacts an upstream provider. */
class ServerBalanceClient {
    fun fetch(config: RelayConfig): ServerBalanceSnapshot {
        val base = validateServerUrl(config.serverUrl)
        require(config.apiToken.isNotBlank() && !config.apiToken.contains('\n') && !config.apiToken.contains('\r')) {
            "余额查询令牌无效"
        }
        val connection = URI(base.toString().trimEnd('/') + API_PATH).toURL().openConnection() as HttpURLConnection
        var code = 0
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer ${config.apiToken}")
            connection.setRequestProperty("Accept", "application/json")
            code = connection.responseCode
            if (code !in 200..299) throw RelayApiException(code, when (code) {
                401 -> "余额查询令牌无效、API 未开启或出口 IP 未获允许"
                403 -> "当前出口 IP 不在服务端白名单内，或余额 API 未开启"
                else -> "余额服务返回 HTTP $code"
            })
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1048576)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return parse(JSONObject(bytes.toString(Charsets.UTF_8)))
        } catch (error: RelayApiException) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            throw RelayApiException(code, "余额服务请求超时")
        } catch (error: java.io.IOException) {
            throw RelayApiException(code, "无法连接余额服务，请检查网络和服务端地址")
        } catch (error: Exception) {
            // Never propagate response bodies, bearer tokens or server-provided error text.
            throw RelayApiException(code, "余额服务响应格式无效，请检查服务端版本")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val API_PATH = "/api/v1/upstream-balance/status"

        fun validateServerUrl(value: String): URI {
            require(value.length <= 2048)
            val uri = URI(value)
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            // Direct server IP deployments may use HTTP with the server-side token/IP allowlist.
            require(uri.scheme in setOf("https", "http")) {
                "服务器地址须使用 HTTP 或 HTTPS"
            }
            return uri
        }

        internal fun parse(document: JSONObject): ServerBalanceSnapshot {
            require(document.getInt("code") == 0)
            val data = document.getJSONObject("data")
            val generatedAt = Instant.parse(data.getString("generated_at")).toEpochMilli()
            val interval = data.getLong("interval_seconds").coerceIn(30, 86400)
            val entries = data.getJSONArray("monitors")
            require(entries.length() <= 2000)
            val monitors = (0 until entries.length()).map { index ->
                val row = entries.getJSONObject(index)
                val id = row.getString("id").also { require(it.isNotBlank() && it.length <= 128) }
                val name = row.getString("name").also { require(it.isNotBlank() && it.length <= 256) }
                val threshold = row.getDouble("threshold").also { require(it.isFinite()) }
                val stale = row.getBoolean("stale")
                val checkedAt = if (stale) generatedAt else if (row.isNull("checked_at")) 0L else Instant.parse(row.getString("checked_at")).toEpochMilli()
                val status = row.getString("status")
                val monitor = RemoteMonitorConfig("server", id, 1, 0, name, "", "GET", null, null,
                    jsonPath = "", operator = "<", threshold = threshold, multiplier = 1.0, offset = 0.0,
                    pollIntervalSeconds = interval, timeoutSeconds = 20,
                    errorThreshold = data.optInt("error_threshold", 3).coerceIn(1, 100),
                    repeatAlertSeconds = data.optLong("repeat_alert_seconds", 300).coerceAtLeast(0),
                    enabled = row.getBoolean("enabled"), provider = "server")
                val result = if (stale) {
                    Result.failure(RelayApiException(0, "服务端余额数据已过期，请检查定时查询"))
                } else if (status == "unlimited") {
                    Result.success(RemoteMonitorTestResult(checkedAt, 200, null, null, false))
                } else if (status in setOf("ok", "low")) {
                    val balance = row.getDouble("balance").also { require(it.isFinite()) }
                    Result.success(RemoteMonitorTestResult(checkedAt, 200, balance, balance, balance < threshold))
                } else Result.failure(RelayApiException(0, if (checkedAt == 0L) "等待服务端首次查询" else "上游余额查询失败，请检查服务端"))
                ServerBalanceItem(monitor, checkedAt, result)
            }
            require(monitors.map { it.config.id }.toSet().size == monitors.size)
            return ServerBalanceSnapshot(interval, monitors)
        }
    }
}

data class ServerBalanceSnapshot(val intervalSeconds: Long, val monitors: List<ServerBalanceItem>)
data class ServerBalanceItem(val config: RemoteMonitorConfig, val checkedAt: Long, val result: Result<RemoteMonitorTestResult>)
