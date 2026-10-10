package com.creditinform.app.ui

import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorStatus
import kotlin.math.abs

internal data class RemoteMonitorRow(
    val id: String,
    val config: RemoteMonitorConfig?,
    val status: RemoteMonitorStatus?
)

internal enum class MonitorValueTone {
    NO_DATA,
    ALERT,
    WARNING,
    HEALTHY
}

internal fun mergeRemoteMonitorRows(
    configs: List<RemoteMonitorConfig>,
    statuses: List<RemoteMonitorStatus>
): List<RemoteMonitorRow> {
    val statusesById = statuses.associateBy(RemoteMonitorStatus::id)
    val configuredIds = configs.mapTo(mutableSetOf(), RemoteMonitorConfig::id)
    return configs.map { config ->
        RemoteMonitorRow(config.id, config, statusesById[config.id])
    } + statuses.filterNot { it.id in configuredIds }.map { status ->
        RemoteMonitorRow(status.id, null, status)
    }
}

internal fun moveRemoteMonitorId(
    ids: List<String>,
    draggedId: String,
    targetId: String
): List<String> {
    val fromIndex = ids.indexOf(draggedId)
    val targetIndex = ids.indexOf(targetId)
    if (fromIndex < 0 || targetIndex < 0 || fromIndex == targetIndex) return ids
    return ids.toMutableList().apply {
        removeAt(fromIndex)
        add(targetIndex, draggedId)
    }
}

internal fun monitorTestErrorMessage(
    monitorName: String,
    testError: String?,
    refreshError: String?
): String = buildList {
    testError?.takeIf(String::isNotBlank)?.let {
        add("$monitorName 测试失败：$it")
    }
    refreshError?.takeIf(String::isNotBlank)?.let {
        add("同步最新值失败：$it")
    }
}.joinToString("\n")

internal fun monitorValueTone(
    value: Double?,
    threshold: Double,
    breached: Boolean
): MonitorValueTone = when {
    value == null -> MonitorValueTone.NO_DATA
    breached || value <= threshold -> MonitorValueTone.ALERT
    value <= threshold + abs(threshold) * 0.20 -> MonitorValueTone.WARNING
    else -> MonitorValueTone.HEALTHY
}
