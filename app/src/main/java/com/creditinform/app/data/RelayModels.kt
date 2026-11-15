package com.creditinform.app.data

data class RelayConfig(
    val enabled: Boolean = false,
    val vibrateEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val alarmVolumePercent: Int = 100,
    val ringtoneUri: String = ""
) {
    val isComplete: Boolean get() = true
    val hasAdminAccess: Boolean get() = true
    fun normalized(): RelayConfig = copy(alarmVolumePercent = alarmVolumePercent.coerceIn(1, 100))

    companion object {
        const val MAX_NAME_LENGTH = 128
        const val MAX_URL_LENGTH = 2048
        fun freshInstall(): RelayConfig = RelayConfig()
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
    val lastError: String = ""
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

/** Monitor configuration stored encrypted on this phone. */
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
    val gatewaySecretKey: String = ""
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
    val gatewaySecretKey: String = ""
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
