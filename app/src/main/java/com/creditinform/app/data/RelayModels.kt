package com.creditinform.app.data

import com.creditinform.app.BuildConfig
import java.util.UUID

data class RelayConfig(
    val serverUrl: String = "",
    val groupId: String = INTERNAL_GROUP_ID,
    val sharedToken: String = "",
    val adminToken: String = "",
    val deviceId: String = UUID.randomUUID().toString(),
    val deviceName: String = INTERNAL_DEVICE_NAME,
    val enabled: Boolean = false,
    val vibrateEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val alarmVolumePercent: Int = 100,
    val ringtoneUri: String = ""
) {
    val isComplete: Boolean
        get() = serverUrl.length in 1..MAX_URL_LENGTH &&
            (serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) &&
            sharedToken.isNotBlank() && isValidIdentifier(deviceId)

    val hasAdminAccess: Boolean
        get() = (serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) &&
            serverUrl.length <= MAX_URL_LENGTH &&
            adminToken.isNotBlank()

    fun normalized(): RelayConfig = copy(
        serverUrl = serverUrl.trim().trimEnd('/'),
        groupId = INTERNAL_GROUP_ID,
        sharedToken = sharedToken.trim(),
        adminToken = adminToken.trim(),
        deviceId = deviceId.trim().ifBlank { UUID.randomUUID().toString() },
        deviceName = INTERNAL_DEVICE_NAME,
        alarmVolumePercent = alarmVolumePercent.coerceIn(1, 100)
    )

    companion object {
        const val DEFAULT_SERVER_URL = "http://70.39.195.177:8047"
        // Previous default deployments; saved configs still pointing here follow the move.
        private val RETIRED_SERVER_URLS = setOf("http://15.204.246.22:8047")
        const val INTERNAL_GROUP_ID = "customer-service"
        const val INTERNAL_DEVICE_NAME = "Android 客户端"
        const val MAX_NAME_LENGTH = 128
        const val MAX_URL_LENGTH = 2048
        private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

        fun isValidIdentifier(value: String): Boolean = IDENTIFIER_PATTERN.matches(value)

        fun isValidGroupId(value: String): Boolean = isValidIdentifier(value)

        fun migrateServerUrl(value: String): String =
            if (value.trim().trimEnd('/') in RETIRED_SERVER_URLS) DEFAULT_SERVER_URL else value

        fun freshInstall(): RelayConfig = RelayConfig(
            serverUrl = DEFAULT_SERVER_URL,
            sharedToken = BuildConfig.RELAY_DEFAULT_SHARED_TOKEN
        )
    }
}

enum class RelayConnectionState {
    DISABLED,
    CHECKING,
    ONLINE,
    OFFLINE
}

data class RelayStatus(
    val state: RelayConnectionState = RelayConnectionState.DISABLED,
    val lastCheckedAt: Long = 0,
    val lastOnlineAt: Long = 0,
    val lastError: String = "",
    val onlineClients: Int = 0,
    val totalClients: Int = 0
)

data class RelayHealth(
    val service: String,
    val version: String,
    val serverInstanceId: String? = null,
    val serverTime: Long,
    val uptimeSeconds: Long,
    val onlineClients: Int,
    val totalClients: Int,
    val heartbeatTimeoutSeconds: Int
)

data class RelayServerStatus(
    val serverInstanceId: String? = null,
    val serverTime: Long,
    val cursor: Long,
    val onlineClients: Int,
    val totalClients: Int
)

data class RelayClient(
    val groupId: String,
    val id: String,
    val name: String,
    val lastSeenAt: Long
)

data class RelayRegistration(
    val serverTime: Long,
    val cursor: Long,
    val client: RelayClient,
    val onlineClients: Int = 0,
    val totalClients: Int = 0,
    val serverInstanceId: String? = null,
    val cursorToken: String? = null
)

enum class RelayAlertType(val wireValue: String) {
    MANUAL("manual"),
    MONITOR_BREACH("monitor_breach"),
    MONITOR_ERROR("monitor_error"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): RelayAlertType =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class RelayAlarmEvent(
    val eventId: Long,
    val type: RelayAlertType,
    val groupId: String,
    val senderId: String,
    val senderName: String,
    val message: String,
    val createdAt: Long,
    val monitorId: String? = null,
    val monitorName: String? = null,
    val eventUid: String? = null,
    val serverInstanceId: String? = null
) {
    val acknowledgementKey: String
        get() = eventUid?.takeIf { it.isNotBlank() }
            ?.let { "uid:$it" }
            ?: serverInstanceId?.takeIf { it.isNotBlank() }
            ?.let { "$it:$eventId" }
            ?: eventId.toString()

    fun matchesAcknowledgementKey(value: String): Boolean {
        eventUid?.takeIf { it.isNotBlank() }?.let { return value == "uid:$it" }
        val separator = value.lastIndexOf(':')
        val acknowledgedEventId = value.substring(separator + 1).toLongOrNull() ?: return false
        if (acknowledgedEventId != eventId) return false
        if (separator < 0) return serverInstanceId.isNullOrBlank()
        return serverInstanceId.orEmpty() == value.substring(0, separator)
    }
}

data class RelaySendResult(
    val event: RelayAlarmEvent,
    val recipients: Int,
    val onlineRecipients: Int,
    val serverTime: Long
)

data class RelayPollResult(
    val events: List<RelayAlarmEvent>,
    /** Persist this only after all returned events have been stored locally. */
    val cursor: Long,
    val headCursor: Long,
    val serverTime: Long,
    val serverInstanceId: String? = null,
    val cursorToken: String? = null,
    val cursorValid: Boolean = true
)

data class RemoteMonitorConfigStatus(
    val loaded: Boolean,
    val lastLoadedAt: Long,
    val lastError: String
)

data class RemoteMonitorStatus(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val url: String = "",
    val method: String = "GET",
    val operator: String,
    val threshold: Double,
    val pollIntervalSeconds: Long,
    val lastCheckAt: Long,
    val lastSuccessAt: Long,
    val lastHttpCode: Int?,
    val lastRawValue: Double?,
    val lastValue: Double?,
    val lastError: String,
    val breached: Boolean,
    val consecutiveErrors: Int,
    val nextCheckAt: Long
)

data class RemoteMonitorsResult(
    val serverTime: Long,
    val config: RemoteMonitorConfigStatus,
    val monitors: List<RemoteMonitorStatus>
)

/** Editable server-side monitor. Sensitive values may contain [KEEP_PLACEHOLDER]. */
data class RemoteMonitorConfig(
    val groupId: String,
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    val name: String,
    val url: String,
    val method: String,
    val headersJson: String?,
    val body: String?,
    val urlMasked: Boolean = false,
    val headersConfigured: Boolean = false,
    val bodyConfigured: Boolean = false,
    val jsonPath: String,
    val operator: String,
    val threshold: Double,
    val multiplier: Double,
    val offset: Double,
    val pollIntervalSeconds: Long,
    val timeoutSeconds: Int,
    val errorThreshold: Int,
    val repeatAlertSeconds: Long,
    val enabled: Boolean,
    val provider: String = "http",
    val gatewayAccessKey: String = "",
    val gatewaySecretKeyEnv: String = "GATEWAY_SK"
) {
    companion object {
        const val KEEP_PLACEHOLDER = "__KEEP__"
    }
}

data class RemoteMonitorUpsertRequest(
    val name: String,
    val url: String,
    val method: String = "GET",
    val headersJson: String? = null,
    val body: String? = null,
    /** False omits body (preserve on update); true sends a value or explicit JSON null. */
    val bodySpecified: Boolean = body != null,
    val jsonPath: String,
    val operator: String = "<",
    val threshold: Double,
    val multiplier: Double = 1.0,
    val offset: Double = 0.0,
    val pollIntervalSeconds: Long = 60,
    val timeoutSeconds: Int = 15,
    val errorThreshold: Int = 3,
    val repeatAlertSeconds: Long = 300,
    val enabled: Boolean = true,
    /** Send the current revision on updates; null means no optimistic concurrency check. */
    val revision: Long? = null,
    val provider: String = "http",
    val gatewayAccessKey: String = "",
    val gatewaySecretKeyEnv: String = "GATEWAY_SK"
)

data class RemoteMonitorConfigsResult(
    val serverTime: Long,
    val keepPlaceholder: String,
    val monitors: List<RemoteMonitorConfig>
)

data class RemoteMonitorMutationResult(
    val serverTime: Long,
    val keepPlaceholder: String,
    val monitor: RemoteMonitorConfig
)

data class RemoteMonitorDeleteResult(
    val serverTime: Long
)

data class RemoteMonitorTestResult(
    val serverTime: Long,
    val httpCode: Int,
    val rawValue: Double?,
    val value: Double?,
    val breached: Boolean
)

data class RemoteMonitorRefreshResult(
    val ok: Boolean,
    val serverTime: Long,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val results: List<RemoteMonitorRefreshItemResult>
)

data class RemoteMonitorRefreshItemResult(
    val monitorId: String,
    val monitorName: String,
    val ok: Boolean,
    val result: RemoteMonitorRefreshCheckResult? = null,
    val error: RemoteMonitorRefreshError? = null
)

data class RemoteMonitorRefreshCheckResult(
    val httpCode: Int?,
    val rawValue: Double?,
    val value: Double?,
    val breached: Boolean
)

data class RemoteMonitorRefreshError(
    val code: String,
    val message: String,
    val httpCode: Int?
)
