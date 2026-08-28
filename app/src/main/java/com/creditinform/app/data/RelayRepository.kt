package com.creditinform.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.creditinform.app.network.RelayApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RelayRepository private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val api = RelayApi()
    private val lock = Any()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<RelayConfig> = _config.asStateFlow()

    private val _status = MutableStateFlow(
        loadStatus().copy(
            state = if (_config.value.enabled) {
                RelayConnectionState.OFFLINE
            } else {
                RelayConnectionState.DISABLED
            }
        )
    )
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val _monitorStatuses = MutableStateFlow<List<RemoteMonitorStatus>>(emptyList())
    val monitorStatuses: StateFlow<List<RemoteMonitorStatus>> = _monitorStatuses.asStateFlow()

    private val _monitorConfigStatus = MutableStateFlow(
        RemoteMonitorConfigStatus(loaded = false, lastLoadedAt = 0, lastError = "")
    )
    val monitorConfigStatus: StateFlow<RemoteMonitorConfigStatus> =
        _monitorConfigStatus.asStateFlow()

    private val _remoteMonitorConfigs = MutableStateFlow<List<RemoteMonitorConfig>>(emptyList())
    val remoteMonitorConfigs: StateFlow<List<RemoteMonitorConfig>> =
        _remoteMonitorConfigs.asStateFlow()

    private val _pendingEvents = MutableStateFlow(loadPendingEvents())
    val pendingEvents: StateFlow<List<RelayAlarmEvent>> = _pendingEvents.asStateFlow()

    private val _cursor = MutableStateFlow(preferences.getLong(KEY_CURSOR, 0L))
    val cursor: StateFlow<Long> = _cursor.asStateFlow()

    private val _currentServerInstanceId = MutableStateFlow(
        preferences.getString(KEY_SERVER_INSTANCE_ID, null).normalizedInstanceId()
    )
    val currentServerInstanceId: StateFlow<String?> = _currentServerInstanceId.asStateFlow()

    init {
        if (!preferences.contains(KEY_CURSOR_INITIALIZED) && preferences.contains(KEY_CURSOR)) {
            check(preferences.edit().putBoolean(KEY_CURSOR_INITIALIZED, true).commit()) {
                "无法迁移报警游标状态"
            }
        }
        // Rewriting also migrates legacy channelId/encryptedToken settings to the current schema.
        persistConfig(_config.value)
    }

    fun saveConfig(value: RelayConfig) = synchronized(lock) {
        val normalized = value.normalized()
        val previous = _config.value
        val identityChanged = !sameIdentity(previous, normalized)
        val adminIdentityChanged = !sameAdminIdentity(previous, normalized)

        if (identityChanged) {
            persistConfigAndResetIdentityLocked(normalized)
            _monitorStatuses.value = emptyList()
            _monitorConfigStatus.value = RemoteMonitorConfigStatus(false, 0, "")
        } else {
            persistConfig(normalized)
        }
        if (adminIdentityChanged) _remoteMonitorConfigs.value = emptyList()
        // Publish only after identity-bound local state has been reset.
        _config.value = normalized
        _status.value = RelayStatus(
            state = if (normalized.enabled) {
                RelayConnectionState.OFFLINE
            } else {
                RelayConnectionState.DISABLED
            }
        )
        persistStatus(_status.value)
    }

    fun setEnabled(enabled: Boolean) {
        saveConfig(config.value.copy(enabled = enabled))
    }

    suspend fun testConnection(candidate: RelayConfig = config.value): Result<RelayServerStatus> =
        withContext(Dispatchers.IO) {
            val normalized = candidate.normalized()
            updateStatusIfCurrent(normalized) {
                it.copy(state = RelayConnectionState.CHECKING, lastError = "")
            }
            relayResult(normalized) {
                validateRelayConfig(normalized, requireEnabled = false)
                val serverStatus = api.status(normalized)
                val monitors = api.monitors(normalized)
                recordRemoteMonitorsIfCurrent(normalized, monitors)
                recordOnlineIfCurrent(
                    candidate = normalized,
                    onlineClients = serverStatus.onlineClients,
                    totalClients = serverStatus.totalClients
                )
                serverStatus
            }
        }

    suspend fun register(): Result<RelayRegistration> = withContext(Dispatchers.IO) {
        val current = config.value
        relayResult(current) {
            validateRelayConfig(current)
            api.register(current).also {
                reconcileServerStateIfCurrent(
                    candidate = current,
                    serverInstanceId = it.serverInstanceId,
                    serverCursor = it.cursor,
                    initializeIfNeeded = true,
                    checkCursorRegression = true
                )
                recordOnlineIfCurrent(current, it.onlineClients, it.totalClients)
            }
        }
    }

    suspend fun heartbeat(): Result<RelayRegistration> = withContext(Dispatchers.IO) {
        val current = config.value
        relayResult(current) {
            validateRelayConfig(current)
            api.heartbeat(current).also {
                reconcileServerStateIfCurrent(
                    candidate = current,
                    serverInstanceId = it.serverInstanceId,
                    serverCursor = it.cursor,
                    initializeIfNeeded = true,
                    checkCursorRegression = true
                )
                recordOnlineIfCurrent(current, it.onlineClients, it.totalClients)
            }
        }
    }

    suspend fun sendAlarm(message: String): Result<RelaySendResult> = withContext(Dispatchers.IO) {
        val current = config.value
        relayResult(current) {
            validateRelayConfig(current)
            val text = message.trim()
            require(text.isNotEmpty()) { "报警内容不能为空" }
            require(text.length <= MAX_MESSAGE_LENGTH) {
                "报警内容不能超过 $MAX_MESSAGE_LENGTH 个字符"
            }
            api.sendAlarm(current, text).also { recordOnlineIfCurrent(current) }
        }
    }

    suspend fun pollOnce(waitSeconds: Int = RelayApi.DEFAULT_POLL_SECONDS): Result<RelayPollResult> =
        withContext(Dispatchers.IO) {
            val current = config.value
            relayResult(current) {
                validateRelayConfig(current)
                val response = api.poll(current, cursor.value, waitSeconds)
                if (!sameRelayConnection(current, config.value)) {
                    throw CancellationException("通信配置已变更")
                }
                val reset = reconcileServerStateIfCurrent(
                    candidate = current,
                    serverInstanceId = response.serverInstanceId,
                    serverCursor = response.headCursor,
                    initializeIfNeeded = false,
                    checkCursorRegression = true
                )
                recordOnlineIfCurrent(current)
                if (reset) response.copy(events = emptyList(), cursor = 0L) else response
            }
        }

    /** Store events before sounding them; commit [RelayPollResult.cursor] afterwards. */
    fun processPendingEvents(
        candidate: RelayConfig,
        poll: RelayPollResult,
        process: (RelayAlarmEvent) -> Unit
    ) =
        synchronized(lock) {
            if (!sameRelayConnection(candidate, config.value)) return@synchronized
            val current = config.value
            val currentInstanceId = _currentServerInstanceId.value
            val responseInstanceId = poll.serverInstanceId.normalizedInstanceId()
            if (responseInstanceId != currentInstanceId) {
                return@synchronized
            }
            val existing = _pendingEvents.value
                .mapTo(hashSetOf()) { it.groupId to it.acknowledgementKey }
            val relevant = poll.events
                .filter { it.groupId == current.groupId && it.senderId != current.deviceId }
                .filter {
                    val eventInstanceId = it.serverInstanceId.normalizedInstanceId()
                    eventInstanceId == currentInstanceId
                }
                .distinctBy { it.groupId to it.acknowledgementKey }
                .sortedBy { it.eventId }
            val added = relevant.filter { (it.groupId to it.acknowledgementKey) !in existing }
            val updated = (_pendingEvents.value + added)
                .distinctBy { it.groupId to it.acknowledgementKey }
                .sortedBy { it.eventId }
            if (added.isNotEmpty()) {
                persistPendingEvents(updated)
                _pendingEvents.value = updated
            }
            val pendingKeys = updated.mapTo(hashSetOf()) { it.groupId to it.acknowledgementKey }
            relevant
                .filter { (it.groupId to it.acknowledgementKey) in pendingKeys }
                .forEach(process)
        }

    fun acknowledgeThrough(candidate: RelayConfig, poll: RelayPollResult) {
        synchronized(lock) {
            if (!sameRelayConnection(candidate, config.value)) return@synchronized
            val responseInstanceId = poll.serverInstanceId.normalizedInstanceId()
            if (responseInstanceId != _currentServerInstanceId.value) {
                return@synchronized
            }
            if (poll.cursor <= _cursor.value) return@synchronized
            check(
                preferences.edit()
                    .putLong(KEY_CURSOR, poll.cursor)
                    .putBoolean(KEY_CURSOR_INITIALIZED, true)
                    .commit()
            ) { "无法保存报警游标" }
            _cursor.value = poll.cursor
        }
    }

    fun acknowledgeLocally(acknowledgementKey: String) = synchronized(lock) {
        val currentGroupId = config.value.groupId
        val updated = _pendingEvents.value.filterNot { event ->
            event.groupId == currentGroupId && event.matchesAcknowledgementKey(acknowledgementKey)
        }
        if (updated.size == _pendingEvents.value.size) return@synchronized
        persistPendingEvents(updated)
        _pendingEvents.value = updated
    }

    suspend fun refreshMonitors(): Result<RemoteMonitorsResult> = withContext(Dispatchers.IO) {
        val current = config.value
        relayResult(current) {
            validateRelayConfig(current, requireEnabled = false)
            api.monitors(current).also {
                recordRemoteMonitorsIfCurrent(current, it)
                recordOnlineIfCurrent(current)
            }
        }
    }

    suspend fun listRemoteMonitorConfigs(): Result<RemoteMonitorConfigsResult> =
        withContext(Dispatchers.IO) {
            adminResult {
                val current = config.value
                validateAdminConfig(current)
                api.listMonitorConfigs(current).also {
                    if (sameAdminIdentity(current, config.value)) {
                        _remoteMonitorConfigs.value = it.monitors
                    }
                }
            }
        }

    suspend fun createRemoteMonitor(
        request: RemoteMonitorUpsertRequest
    ): Result<RemoteMonitorMutationResult> = withContext(Dispatchers.IO) {
        adminResult {
            validateUpsert(request)
            val current = config.value
            validateAdminConfig(current)
            api.createMonitor(current, request.copy(revision = null)).also { result ->
                upsertRemoteMonitorIfCurrent(current, result.monitor)
            }
        }
    }

    suspend fun updateRemoteMonitor(
        monitorId: String,
        request: RemoteMonitorUpsertRequest
    ): Result<RemoteMonitorMutationResult> = withContext(Dispatchers.IO) {
        adminResult {
            validateUpsert(request)
            val id = monitorId.trim()
            require(RelayConfig.isValidIdentifier(id)) {
                "监控 ID 必须为 1 到 64 位字母、数字、点、下划线或短横线"
            }
            val current = config.value
            validateAdminConfig(current)
            api.updateMonitor(current, id, request).also { result ->
                upsertRemoteMonitorIfCurrent(current, result.monitor)
            }
        }
    }

    suspend fun deleteRemoteMonitor(
        monitorId: String
    ): Result<RemoteMonitorDeleteResult> = withContext(Dispatchers.IO) {
        adminResult {
            val id = monitorId.trim()
            require(RelayConfig.isValidIdentifier(id)) {
                "监控 ID 必须为 1 到 64 位字母、数字、点、下划线或短横线"
            }
            val current = config.value
            validateAdminConfig(current)
            api.deleteMonitor(current, id).also {
                if (sameAdminIdentity(current, config.value)) {
                    _remoteMonitorConfigs.value =
                        _remoteMonitorConfigs.value.filterNot { monitor -> monitor.id == id }
                }
            }
        }
    }

    suspend fun testRemoteMonitor(
        monitorId: String
    ): Result<RemoteMonitorTestResult> = withContext(Dispatchers.IO) {
        adminResult {
            val id = monitorId.trim()
            require(RelayConfig.isValidIdentifier(id)) {
                "请先保存有效的监控 ID 再测试"
            }
            val current = config.value
            validateAdminConfig(current)
            api.testMonitor(current, id)
        }
    }

    private inline fun <T> relayResult(
        candidate: RelayConfig,
        block: () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        recordOfflineIfCurrent(candidate, error)
        Result.failure(error)
    }

    private inline fun <T> adminResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun recordOnlineIfCurrent(
        candidate: RelayConfig,
        onlineClients: Int? = null,
        totalClients: Int? = null
    ) {
        val now = System.currentTimeMillis()
        updateStatusIfCurrent(candidate) { previous ->
            previous.copy(
                state = RelayConnectionState.ONLINE,
                lastCheckedAt = now,
                lastOnlineAt = now,
                lastError = "",
                onlineClients = onlineClients ?: previous.onlineClients,
                totalClients = totalClients ?: previous.totalClients
            )
        }
    }

    private fun recordOfflineIfCurrent(candidate: RelayConfig, error: Exception) {
        val now = System.currentTimeMillis()
        updateStatusIfCurrent(candidate) { previous ->
            previous.copy(
                state = if (config.value.enabled) {
                    RelayConnectionState.OFFLINE
                } else {
                    RelayConnectionState.DISABLED
                },
                lastCheckedAt = now,
                lastError = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun updateStatusIfCurrent(
        candidate: RelayConfig,
        transform: (RelayStatus) -> RelayStatus
    ) = synchronized(lock) {
        if (!sameRelayConnection(candidate, config.value)) return@synchronized
        val updated = transform(_status.value)
        _status.value = updated
        persistStatus(updated)
    }

    private fun reconcileServerStateIfCurrent(
        candidate: RelayConfig,
        serverInstanceId: String?,
        serverCursor: Long,
        initializeIfNeeded: Boolean,
        checkCursorRegression: Boolean
    ): Boolean = synchronized(lock) {
        if (!sameRelayConnection(candidate, config.value)) {
            throw CancellationException("通信配置已变更")
        }

        val incomingInstanceId = serverInstanceId.normalizedInstanceId()
        val storedInstanceId = _currentServerInstanceId.value
        val initialized = preferences.getBoolean(KEY_CURSOR_INITIALIZED, false)
        val decision = decideRelayServerState(
            storedInstanceId = storedInstanceId,
            incomingInstanceId = incomingInstanceId,
            cursor = _cursor.value,
            serverCursor = serverCursor,
            cursorInitialized = initialized,
            initializeIfNeeded = initializeIfNeeded,
            checkCursorRegression = checkCursorRegression
        )
        if (!decision.changed) return@synchronized false

        val editor = preferences.edit()
            .putLong(KEY_CURSOR, decision.cursor)
            .putBoolean(KEY_CURSOR_INITIALIZED, decision.cursorInitialized)
        if (decision.serverInstanceId != null) {
            editor.putString(KEY_SERVER_INSTANCE_ID, decision.serverInstanceId)
        } else {
            editor.remove(KEY_SERVER_INSTANCE_ID)
        }
        check(editor.commit()) { "无法保存服务端实例和报警游标" }
        _cursor.value = decision.cursor
        _currentServerInstanceId.value = decision.serverInstanceId
        decision.resetCursor
    }

    private fun recordRemoteMonitorsIfCurrent(
        candidate: RelayConfig,
        result: RemoteMonitorsResult
    ) = synchronized(lock) {
        if (!sameIdentity(candidate, config.value)) return@synchronized
        _monitorStatuses.value = result.monitors
        _monitorConfigStatus.value = result.config
    }

    private fun upsertRemoteMonitorIfCurrent(candidate: RelayConfig, monitor: RemoteMonitorConfig) {
        if (!sameAdminIdentity(candidate, config.value)) return
        val updated = _remoteMonitorConfigs.value.toMutableList()
        val index = updated.indexOfFirst { it.id == monitor.id }
        if (index >= 0) updated[index] = monitor else updated.add(monitor)
        _remoteMonitorConfigs.value = updated.toList()
    }

    private fun validateRelayConfig(value: RelayConfig, requireEnabled: Boolean = true) {
        if (requireEnabled) require(value.enabled) { "通信功能未启用" }
        validateServerUrl(value.serverUrl)
        require(RelayConfig.isValidIdentifier(value.groupId)) {
            "通信组必须为 1 到 64 位字母、数字、点、下划线或短横线"
        }
        require(value.sharedToken.isNotBlank()) { "共享密钥不能为空" }
        require(RelayConfig.isValidIdentifier(value.deviceId)) {
            "客户端 ID 必须为 1 到 64 位字母、数字、点、下划线或短横线"
        }
        require(value.deviceName.length in 1..RelayConfig.MAX_NAME_LENGTH) {
            "客户端名称必须为 1 到 ${RelayConfig.MAX_NAME_LENGTH} 个字符"
        }
    }

    private fun validateAdminConfig(value: RelayConfig) {
        validateServerUrl(value.serverUrl)
        require(RelayConfig.isValidIdentifier(value.groupId)) {
            "通信组必须为 1 到 64 位字母、数字、点、下划线或短横线"
        }
        require(value.adminToken.isNotBlank()) { "管理员密钥不能为空" }
    }

    private fun validateUpsert(value: RemoteMonitorUpsertRequest) {
        require(value.name.isNotBlank() && value.name.length <= RelayConfig.MAX_NAME_LENGTH) {
            "监控名称必须为 1 到 ${RelayConfig.MAX_NAME_LENGTH} 个字符"
        }
        require(value.url.length in 1..RelayConfig.MAX_URL_LENGTH) {
            "监控地址不能超过 ${RelayConfig.MAX_URL_LENGTH} 个字符"
        }
        val uri = runCatching { URI(value.url.trim()) }.getOrNull()
        require(uri != null && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
            "监控地址必须是有效的 http:// 或 https:// 地址"
        }
        require(value.method.uppercase() in setOf("GET", "POST")) { "请求方法仅支持 GET 或 POST" }
        require(value.jsonPath.isNotBlank() && value.jsonPath.length <= MAX_JSON_PATH_LENGTH) {
            "JSON 字段路径必须为 1 到 $MAX_JSON_PATH_LENGTH 个字符"
        }
        require(value.operator in SUPPORTED_OPERATORS) { "比较符仅支持 <、<=、>、>=、= 或 ==" }
        require(value.threshold.isFinite() && value.multiplier.isFinite() && value.offset.isFinite()) {
            "阈值、倍率和偏移量必须是有效数值"
        }
        require(value.pollIntervalSeconds >= 5) { "检测间隔不能少于 5 秒" }
        require(value.timeoutSeconds in 1..60) { "超时时间必须为 1 到 60 秒" }
        require(value.errorThreshold in 1..100) { "连续错误阈值必须为 1 到 100" }
        require(value.repeatAlertSeconds == 0L || value.repeatAlertSeconds >= 15) {
            "重复报警间隔必须为 0，或不少于 15 秒"
        }
        value.headersJson?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
    }

    private fun validateServerUrl(value: String) {
        require(value.length in 1..RelayConfig.MAX_URL_LENGTH) {
            "服务端地址不能超过 ${RelayConfig.MAX_URL_LENGTH} 个字符"
        }
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(
            uri != null && (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank()
        ) { "服务端地址必须是有效的 http:// 或 https:// 地址" }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "服务端地址不能包含查询参数或片段"
        }
    }

    private fun sameIdentity(first: RelayConfig, second: RelayConfig): Boolean =
        first.serverUrl == second.serverUrl &&
            first.groupId == second.groupId &&
            first.deviceId == second.deviceId &&
            first.sharedToken == second.sharedToken

    private fun sameRelayConnection(first: RelayConfig, second: RelayConfig): Boolean =
        sameIdentity(first, second) && first.enabled == second.enabled

    private fun sameAdminIdentity(first: RelayConfig, second: RelayConfig): Boolean =
        first.serverUrl == second.serverUrl &&
            first.groupId == second.groupId &&
            first.adminToken == second.adminToken

    private fun loadConfig(): RelayConfig = runCatching {
        val json = JSONObject(preferences.getString(KEY_CONFIG, "{}") ?: "{}")
        val encryptedSharedToken = json.optString("encryptedSharedToken")
            .ifBlank { json.optString("encryptedToken") }
        val encryptedAdminToken = json.optString("encryptedAdminToken")
        RelayConfig(
            serverUrl = json.optString("serverUrl"),
            groupId = json.optString("groupId").ifBlank { json.optString("channelId") },
            sharedToken = decryptSecret(encryptedSharedToken).ifBlank {
                json.optString("sharedToken").ifBlank { json.optString("authToken") }
            },
            adminToken = decryptSecret(encryptedAdminToken).ifBlank {
                json.optString("adminToken")
            },
            deviceId = json.optString("deviceId").ifBlank { UUID.randomUUID().toString() },
            deviceName = json.optString("deviceName", defaultDeviceName()),
            enabled = json.optBoolean("enabled", false),
            vibrateEnabled = json.optBoolean("vibrateEnabled", true),
            soundEnabled = json.optBoolean("soundEnabled", true),
            alarmVolumePercent = json.optInt("alarmVolumePercent", 100),
            ringtoneUri = json.optString("ringtoneUri")
        ).normalized()
    }.getOrElse { RelayConfig(deviceName = defaultDeviceName()) }

    private fun configJson(value: RelayConfig): JSONObject = JSONObject().apply {
            put("serverUrl", value.serverUrl)
            put("groupId", value.groupId)
            put("encryptedSharedToken", encryptSecret(value.sharedToken))
            put("encryptedAdminToken", encryptSecret(value.adminToken))
            put("deviceId", value.deviceId)
            put("deviceName", value.deviceName)
            put("enabled", value.enabled)
            put("vibrateEnabled", value.vibrateEnabled)
            put("soundEnabled", value.soundEnabled)
            put("alarmVolumePercent", value.alarmVolumePercent)
            put("ringtoneUri", value.ringtoneUri)
        }

    private fun persistConfig(value: RelayConfig) {
        check(preferences.edit().putString(KEY_CONFIG, configJson(value).toString()).commit()) {
            "无法保存通信配置"
        }
    }

    private fun persistConfigAndResetIdentityLocked(value: RelayConfig) {
        check(
            preferences.edit()
                .putString(KEY_CONFIG, configJson(value).toString())
                .putString(KEY_PENDING_EVENTS, "[]")
                .putLong(KEY_CURSOR, 0L)
                .putBoolean(KEY_CURSOR_INITIALIZED, false)
                .remove(KEY_SERVER_INSTANCE_ID)
                .commit()
        ) { "无法保存通信配置并重置报警状态" }
        _pendingEvents.value = emptyList()
        _cursor.value = 0L
        _currentServerInstanceId.value = null
    }

    private fun loadStatus(): RelayStatus = runCatching {
        val json = JSONObject(preferences.getString(KEY_STATUS, "{}") ?: "{}")
        RelayStatus(
            state = runCatching {
                RelayConnectionState.valueOf(json.optString("state"))
            }.getOrDefault(RelayConnectionState.DISABLED),
            lastCheckedAt = json.optLong("lastCheckedAt"),
            lastOnlineAt = json.optLong("lastOnlineAt"),
            lastError = json.optString("lastError"),
            onlineClients = json.optInt("onlineClients"),
            totalClients = json.optInt("totalClients")
        )
    }.getOrDefault(RelayStatus())

    private fun persistStatus(value: RelayStatus) {
        val json = JSONObject().apply {
            put("state", value.state.name)
            put("lastCheckedAt", value.lastCheckedAt)
            put("lastOnlineAt", value.lastOnlineAt)
            put("lastError", value.lastError)
            put("onlineClients", value.onlineClients)
            put("totalClients", value.totalClients)
        }
        preferences.edit().putString(KEY_STATUS, json.toString()).apply()
    }

    private fun loadPendingEvents(): List<RelayAlarmEvent> = runCatching {
        val array = JSONArray(preferences.getString(KEY_PENDING_EVENTS, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).toRelayAlarmEvent())
            }
        }
    }.getOrDefault(emptyList())

    private fun persistPendingEvents(events: List<RelayAlarmEvent>) {
        val array = JSONArray().apply { events.forEach { put(it.toJson()) } }
        check(preferences.edit().putString(KEY_PENDING_EVENTS, array.toString()).commit()) {
            "无法保存待处理报警"
        }
    }

    private fun RelayAlarmEvent.toJson() = JSONObject().apply {
        put("eventId", eventId)
        put("type", type.wireValue)
        put("groupId", groupId)
        put("senderId", senderId)
        put("senderName", senderName)
        put("message", message)
        put("createdAt", createdAt)
        put("monitorId", monitorId ?: JSONObject.NULL)
        put("monitorName", monitorName ?: JSONObject.NULL)
        put("eventUid", eventUid ?: JSONObject.NULL)
        put("serverInstanceId", serverInstanceId ?: JSONObject.NULL)
    }

    private fun JSONObject.toRelayAlarmEvent() = RelayAlarmEvent(
        eventId = getLong("eventId"),
        type = RelayAlertType.fromWire(optString("type")),
        groupId = optString("groupId"),
        senderId = optString("senderId"),
        senderName = optString("senderName"),
        message = optString("message"),
        createdAt = optLong("createdAt"),
        monitorId = if (isNull("monitorId")) null else optString("monitorId"),
        monitorName = if (isNull("monitorName")) null else optString("monitorName"),
        eventUid = if (isNull("eventUid")) null else optString("eventUid"),
        serverInstanceId = if (isNull("serverInstanceId")) null else optString("serverInstanceId")
    )

    private fun encryptSecret(secret: String): String {
        if (secret.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(secret.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        return "$iv:$ciphertext"
    }

    private fun decryptSecret(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP))
                )
            }
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android 客户端" }

    private fun String?.normalizedInstanceId(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val PREFS_NAME = "credit_inform_relay"
        private const val KEY_CONFIG = "config"
        private const val KEY_STATUS = "relay_status_v2"
        private const val KEY_PENDING_EVENTS = "pending_events_v2"
        private const val KEY_CURSOR = "event_cursor_v2"
        private const val KEY_CURSOR_INITIALIZED = "event_cursor_initialized_v2"
        private const val KEY_SERVER_INSTANCE_ID = "server_instance_id_v2"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "credit_inform_relay_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val MAX_MESSAGE_LENGTH = 500
        private const val MAX_JSON_PATH_LENGTH = 512
        private val SUPPORTED_OPERATORS = setOf("<", "<=", ">", ">=", "=", "==")

        @Volatile
        private var instance: RelayRepository? = null

        fun get(context: Context): RelayRepository = instance ?: synchronized(this) {
            instance ?: RelayRepository(context.applicationContext).also { instance = it }
        }
    }
}
