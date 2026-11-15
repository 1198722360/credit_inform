package com.creditinform.app.data

import com.creditinform.app.network.RelayApiException
import org.json.JSONObject

internal data class LocalMonitorState(
    val checkedAt: Long = 0,
    val successAt: Long = 0,
    val httpCode: Int? = null,
    val raw: Double? = null,
    val value: Double? = null,
    val error: String = "",
    val breached: Boolean = false,
    val errors: Int = 0,
    val nextAt: Long = 0,
    val lastAlertAt: Long = 0,
    val alertKind: String = ""
) {
    fun checked(config: RemoteMonitorConfig, result: Result<RemoteMonitorTestResult>, now: Long,
                allowAlarm: Boolean): Pair<LocalMonitorState, RelayAlertType?> {
        val success = result.getOrNull()
        val count = if (success != null) 0 else (errors + 1).coerceAtMost(1000000)
        val kind = when {
            success?.breached == true -> "breach"
            success == null && count >= config.errorThreshold -> "error"
            else -> ""
        }
        val fire = allowAlarm && kind.isNotEmpty() && (kind != alertKind ||
            (config.repeatAlertSeconds > 0 && now - lastAlertAt >= config.repeatAlertSeconds * 1000))
        val next = copy(checkedAt = now, successAt = if (success != null) now else successAt,
            httpCode = success?.httpCode ?: (result.exceptionOrNull() as? RelayApiException)?.httpCode?.takeIf { it > 0 },
            raw = success?.rawValue ?: raw, value = success?.value ?: value,
            error = if (success != null) "" else result.exceptionOrNull()?.message.orEmpty(),
            breached = success?.breached ?: breached, errors = count,
            nextAt = now + config.pollIntervalSeconds * 1000,
            lastAlertAt = if (fire) now else lastAlertAt,
            alertKind = if (kind.isEmpty()) "" else if (fire) kind else alertKind)
        return next to if (!fire) null else if (kind == "breach") RelayAlertType.MONITOR_BREACH else RelayAlertType.MONITOR_ERROR
    }

    fun status(config: RemoteMonitorConfig) = RemoteMonitorStatus(config.id, config.name, config.enabled,
        config.url, config.method, config.operator, config.threshold, config.pollIntervalSeconds,
        checkedAt, successAt, httpCode, raw, value, error, breached, errors, if (config.enabled) nextAt else 0)

    fun toJson() = JSONObject().put("checkedAt", checkedAt).put("successAt", successAt)
        .put("httpCode", httpCode).put("raw", raw).put("value", value).put("error", error)
        .put("breached", breached).put("errors", errors).put("nextAt", nextAt)
        .put("lastAlertAt", lastAlertAt).put("alertKind", alertKind)

    companion object {
        fun fromJson(j: JSONObject) = LocalMonitorState(j.optLong("checkedAt"), j.optLong("successAt"),
            if (j.has("httpCode")) j.getInt("httpCode") else null,
            if (j.has("raw")) j.getDouble("raw") else null,
            if (j.has("value")) j.getDouble("value") else null, j.optString("error"),
            j.optBoolean("breached"), j.optInt("errors"), j.optLong("nextAt"),
            j.optLong("lastAlertAt"), j.optString("alertKind"))
    }
}

internal fun RemoteMonitorConfig.toLocalJson(): JSONObject = JSONObject()
    .put("id", id).put("revision", revision).put("updatedAt", updatedAt).put("name", name)
    .put("url", url).put("method", method).put("headersJson", headersJson).put("body", body)
    .put("jsonPath", jsonPath).put("operator", operator).put("threshold", threshold)
    .put("multiplier", multiplier).put("offset", offset).put("pollIntervalSeconds", pollIntervalSeconds)
    .put("timeoutSeconds", timeoutSeconds).put("errorThreshold", errorThreshold)
    .put("repeatAlertSeconds", repeatAlertSeconds).put("enabled", enabled).put("provider", provider)
    .put("gatewayAccessKey", gatewayAccessKey).put("gatewaySecretKey", gatewaySecretKey)

internal fun localMonitorFromJson(j: JSONObject) = RemoteMonitorConfig(
    groupId = "local", id = j.getString("id"), revision = j.getLong("revision"), updatedAt = j.getLong("updatedAt"),
    name = j.getString("name"), url = j.getString("url"), method = j.getString("method"),
    headersJson = if (j.has("headersJson")) j.getString("headersJson") else null,
    body = if (j.has("body")) j.getString("body") else null,
    headersConfigured = j.has("headersJson"), bodyConfigured = j.has("body"),
    jsonPath = j.getString("jsonPath"), operator = j.getString("operator"), threshold = j.getDouble("threshold"),
    multiplier = j.getDouble("multiplier"), offset = j.getDouble("offset"),
    pollIntervalSeconds = j.getLong("pollIntervalSeconds"), timeoutSeconds = j.getInt("timeoutSeconds"),
    errorThreshold = j.getInt("errorThreshold"), repeatAlertSeconds = j.getLong("repeatAlertSeconds"),
    enabled = j.getBoolean("enabled"), provider = j.getString("provider"),
    gatewayAccessKey = j.optString("gatewayAccessKey"), gatewaySecretKey = j.optString("gatewaySecretKey"))
