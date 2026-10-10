package com.creditinform.app.data

import com.creditinform.app.network.RelayApi
import kotlinx.coroutines.CancellationException

internal enum class RemoteMonitorSnapshotPublication {
    PUBLISHED,
    IGNORED_STALE,
    REJECTED_IDENTITY
}

internal class RemoteMonitorSnapshotGate {
    private var latestServerTime: Long? = null

    fun reset() {
        latestServerTime = null
    }

    fun publish(
        serverTime: Long,
        relayIdentityMatches: Boolean,
        adminIdentityMatches: Boolean,
        requireAdminIdentity: Boolean,
        block: () -> Unit
    ): RemoteMonitorSnapshotPublication {
        if (!relayIdentityMatches || (requireAdminIdentity && !adminIdentityMatches)) {
            return RemoteMonitorSnapshotPublication.REJECTED_IDENTITY
        }
        if (latestServerTime?.let { serverTime < it } == true) {
            return RemoteMonitorSnapshotPublication.IGNORED_STALE
        }
        block()
        latestServerTime = serverTime
        return RemoteMonitorSnapshotPublication.PUBLISHED
    }
}

internal fun refreshRemoteMonitorValuesAndPublish(
    api: RelayApi,
    config: RelayConfig,
    publish: (RemoteMonitorsResult) -> RemoteMonitorSnapshotPublication
): RemoteMonitorRefreshResult {
    val refreshResult = api.refreshMonitors(config)
    val latestMonitors = api.monitors(config)
    if (publish(latestMonitors) == RemoteMonitorSnapshotPublication.REJECTED_IDENTITY) {
        throw CancellationException("通信配置已变更")
    }
    return refreshResult
}
