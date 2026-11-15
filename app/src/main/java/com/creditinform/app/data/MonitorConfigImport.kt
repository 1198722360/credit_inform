package com.creditinform.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.util.UUID

/** Portable UTF-8 JSON, independent of Android and of the old relay server. */
internal object MonitorConfigImport {
    const val MAX_BYTES = 1024 * 1024

    fun read(stream: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "配置文件不能超过 1 MiB" }
            output.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }

    fun parse(text: String): List<RemoteMonitorConfig> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件不能超过 1 MiB" }
        val array = try {
            val tokener = JSONTokener(text.removePrefix("\uFEFF"))
            val root = tokener.nextValue()
            require(tokener.nextClean() == '\u0000')
            when (root) {
                is JSONObject -> root.getJSONArray("monitors")
                is JSONArray -> root
                else -> error("Invalid root")
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("配置文件必须是包含 monitors 数组的 JSON 对象，或监控项数组")
        }
        require(array.length() <= 500) { "最多支持导入 500 个监控项" }
        val ids = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            try {
                val j = array.getJSONObject(index)
                val id = j.string("id", UUID.randomUUID().toString())
                require(Regex("[A-Za-z0-9._-]{1,64}").matches(id)) { "监控 ID 格式无效" }
                require(ids.add(id)) { "监控 ID 重复" }
                val headers = when (val value = j.opt("headersJson") ?: j.opt("headers")) {
                    null, JSONObject.NULL -> "{}"
                    is JSONObject -> value.toString()
                    is String -> JSONObject(value).toString()
                    else -> error("请求头必须为 JSON 对象")
                }
                val body = when (val value = j.opt("body")) {
                    null, JSONObject.NULL -> null
                    is String -> value
                    is JSONObject, is JSONArray -> value.toString()
                    else -> error("请求体必须为字符串或 JSON 对象／数组")
                }
                val provider = j.string("provider", "http")
                if (provider == "gateway") require(j.string("gatewaySecretKey", "").isNotBlank()) {
                    "GateWay 需要填写 gatewaySecretKey 的完整 SK，不能只提供服务器环境变量名"
                }
                val request = RemoteMonitorUpsertRequest(
                    name = j.string("name"), url = j.string("url"),
                    method = j.string("method", if (provider == "gateway") "POST" else "GET").uppercase(),
                    headersJson = headers, body = body, jsonPath = j.string("jsonPath"),
                    operator = j.string("operator", "<"), threshold = j.number("threshold"),
                    multiplier = j.number("multiplier", 1.0), offset = j.number("offset", 0.0),
                    pollIntervalSeconds = j.integer("pollIntervalSeconds", 60),
                    timeoutSeconds = j.integer("timeoutSeconds", 15).also { require(it in 1..60) { "超时时间必须为 1 到 60 秒" } }.toInt(),
                    errorThreshold = j.integer("errorThreshold", 3).also { require(it in 1..100) { "连续错误次数必须为 1 到 100" } }.toInt(),
                    repeatAlertSeconds = j.integer("repeatAlertSeconds", 300), enabled = j.boolean("enabled", true),
                    provider = provider, gatewayAccessKey = j.string("gatewayAccessKey", ""),
                    gatewaySecretKey = j.string("gatewaySecretKey", ""))
                validateMonitorRequest(request)
                require(listOf(request.url, headers, body.orEmpty(), request.gatewaySecretKey).none { "__KEEP__" in it }) {
                    "配置包含脱敏占位符，请提供完整接口凭据"
                }
                RemoteMonitorConfig("local", id, System.currentTimeMillis(), System.currentTimeMillis(),
                    request.name, request.url, request.method, headers, body,
                    headersConfigured = JSONObject(headers).length() > 0, bodyConfigured = body != null,
                    jsonPath = request.jsonPath, operator = request.operator, threshold = request.threshold,
                    multiplier = request.multiplier, offset = request.offset, pollIntervalSeconds = request.pollIntervalSeconds,
                    timeoutSeconds = request.timeoutSeconds, errorThreshold = request.errorThreshold,
                    repeatAlertSeconds = request.repeatAlertSeconds, enabled = request.enabled,
                    provider = provider, gatewayAccessKey = request.gatewayAccessKey, gatewaySecretKey = request.gatewaySecretKey)
            } catch (error: Exception) {
                // JSON/URI parser exceptions can contain the full credential-bearing input.
                val detail = if (error is IllegalArgumentException && error !is NumberFormatException) error.message else null
                throw IllegalArgumentException("第 ${index + 1} 个监控项无效：${detail ?: "字段缺失或格式错误"}")
            }
        }
    }

    private fun JSONObject.string(key: String, default: String? = null): String {
        if (!has(key) && default != null) return default
        return (opt(key) as? String) ?: throw IllegalArgumentException("$key 必须为字符串")
    }
    private fun JSONObject.number(key: String, default: Double? = null): Double {
        if (!has(key) && default != null) return default
        val value = opt(key)
        require(value is Number || value is String) { "$key 必须为数值" }
        return value.toString().toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("$key 必须为有效数值")
    }
    private fun JSONObject.integer(key: String, default: Long): Long {
        if (!has(key)) return default
        val value = number(key)
        require(value >= 0 && value <= 604800 && value % 1.0 == 0.0) { "$key 必须为范围内的整数" }
        return value.toLong()
    }
    private fun JSONObject.boolean(key: String, default: Boolean): Boolean =
        if (!has(key)) default else opt(key) as? Boolean ?: throw IllegalArgumentException("$key 必须为布尔值")
}
