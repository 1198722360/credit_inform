package com.creditinform.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.creditinform.app.network.ServerBalanceClient
import com.creditinform.app.network.RelayApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All settings, monitor state and pending alarms belong to this device. */
class RelayRepository private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("credit_inform_relay", Context.MODE_PRIVATE)
    private val lock = Any()
    private val checkMutex = Mutex()
    private var configGeneration = 0L
    private val client = ServerBalanceClient()
    private var nextSyncAt = 0L
    private var loadError = ""
    private val initial = loadDocument()
    private val _config = MutableStateFlow(loadConfig())
    val config = _config.asStateFlow()
    private val _remoteMonitorConfigs = MutableStateFlow(readArray(initial.optJSONArray("monitors"), ::localMonitorFromJson))
    val remoteMonitorConfigs = _remoteMonitorConfigs.asStateFlow()
    private var states = initial.optJSONObject("states")?.let { j ->
        j.keys().asSequence().associateWith { LocalMonitorState.fromJson(j.getJSONObject(it)) }
    }.orEmpty()
    private val _pendingEvents = MutableStateFlow(readArray(initial.optJSONArray("pending")) { it.toRelayAlarmEvent() })
    val pendingEvents = _pendingEvents.asStateFlow()
    private val _monitorStatuses = MutableStateFlow(_remoteMonitorConfigs.value.map { (states[it.id] ?: LocalMonitorState()).status(it) })
    val monitorStatuses = _monitorStatuses.asStateFlow()
    private val _monitorConfigStatus = MutableStateFlow(RemoteMonitorConfigStatus(loadError.isEmpty(), System.currentTimeMillis(), loadError))
    val monitorConfigStatus = _monitorConfigStatus.asStateFlow()
    private val _status = MutableStateFlow(RelayStatus())
    val status = _status.asStateFlow()

    init {
        // Legacy local data stays quarantined until the first successful server sync.
        // It is never loaded or executed by this client, so a failed migration is recoverable.
        saveConfig(_config.value)
    }

    private fun loadDocument(): JSONObject = try {
        val document = preferences.getString("server_balances_v1", null)?.let { JSONObject(decryptSecret(it)) } ?: JSONObject()
        readArray(document.optJSONArray("monitors"), ::localMonitorFromJson)
        readArray(document.optJSONArray("pending")) { it.toRelayAlarmEvent() }
        document.optJSONObject("states")?.let { states ->
            states.keys().forEach { LocalMonitorState.fromJson(states.getJSONObject(it)) }
        }
        document
    } catch (error: Exception) {
        loadError = "无法读取本机加密数据，请保留应用数据并检查设备密钥"
        JSONObject()
    }

    private fun loadConfig(): RelayConfig {
        val j = runCatching { JSONObject(preferences.getString("config", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val token = runCatching { decryptSecret(j.optString("balanceApiToken")) }.getOrDefault("")
        return RelayConfig(enabled = j.optBoolean("enabled") && j.optString("serverUrl").isNotBlank() && token.isNotBlank(), soundEnabled = j.optBoolean("soundEnabled", true),
            vibrateEnabled = j.optBoolean("vibrateEnabled", true), alarmVolumePercent = j.optInt("alarmVolumePercent", 100),
            ringtoneUri = j.optString("ringtoneUri"), serverUrl = j.optString("serverUrl"),
            apiToken = token).normalized()
    }

    fun saveConfig(value: RelayConfig) = synchronized(lock) {
        val normalized = value.normalized()
        if (normalized.serverUrl.isNotEmpty()) ServerBalanceClient.validateServerUrl(normalized.serverUrl)
        require(!normalized.enabled || normalized.isComplete) { "请填写 sub2api 地址和余额查询令牌" }
        val changedServer = normalized.serverUrl != _config.value.serverUrl || normalized.apiToken != _config.value.apiToken
        val j = JSONObject().put("enabled", normalized.enabled).put("soundEnabled", normalized.soundEnabled)
            .put("vibrateEnabled", normalized.vibrateEnabled).put("alarmVolumePercent", normalized.alarmVolumePercent)
            .put("ringtoneUri", normalized.ringtoneUri).put("serverUrl", normalized.serverUrl)
            .put("balanceApiToken", encryptSecret(normalized.apiToken))
        check(preferences.edit().putString("config", j.toString()).commit()) { "无法保存本机设置或参数无效" }
        if (changedServer) persist(emptyList(), emptyMap(), emptyList())
        else if (normalized.enabled && !_config.value.enabled) persist(nextStates = emptyMap())
        _config.value = normalized
        nextSyncAt = 0L
        configGeneration++
        _status.value = RelayStatus(if (normalized.enabled) RelayConnectionState.CHECKING else RelayConnectionState.DISABLED)
    }

    fun setEnabled(enabled: Boolean) = saveConfig(config.value.copy(enabled = enabled))

    private fun persist(monitors: List<RemoteMonitorConfig> = _remoteMonitorConfigs.value,
                        nextStates: Map<String, LocalMonitorState> = states,
                        pending: List<RelayAlarmEvent> = _pendingEvents.value) {
        check(loadError.isEmpty()) { loadError }
        val document = JSONObject().put("monitors", JSONArray().apply { monitors.forEach { put(it.toLocalJson()) } })
            .put("states", JSONObject().apply { nextStates.forEach { (id, state) -> put(id, state.toJson()) } })
            .put("pending", JSONArray().apply { pending.forEach { put(it.toJson()) } })
        check(preferences.edit().putString("server_balances_v1", encryptSecret(document.toString())).commit()) { "无法保存本机监控数据" }
        states = nextStates
        _remoteMonitorConfigs.value = monitors
        _pendingEvents.value = pending
        _monitorStatuses.value = monitors.map { (states[it.id] ?: LocalMonitorState()).status(it) }
    }

    fun acknowledgeLocally(key: String) = synchronized(lock) {
        if (_pendingEvents.value.any { it.matchesAcknowledgementKey(key) }) {
            persist(pending = _pendingEvents.value.filterNot { it.matchesAcknowledgementKey(key) })
        }
    }
    fun acknowledgeAllLocally() = synchronized(lock) {
        if (_pendingEvents.value.isNotEmpty()) persist(pending = emptyList())
    }

    suspend fun testConnection(): Result<Unit> = operation {
        checkMutex.withLock { syncBalances(config.value.enabled) }
        Unit
    }
    suspend fun refreshMonitors(): Result<RemoteMonitorsResult> = operation {
        checkMutex.withLock { syncBalances(config.value.enabled) }
        RemoteMonitorsResult(System.currentTimeMillis(), _monitorConfigStatus.value, _monitorStatuses.value)
    }
    suspend fun listRemoteMonitorConfigs(): Result<RemoteMonitorConfigsResult> = operation {
        RemoteMonitorConfigsResult(System.currentTimeMillis(), "", _remoteMonitorConfigs.value)
    }

    private fun readOnly(): Nothing = error("请在 sub2api 上游余额页面管理监控")
    suspend fun createRemoteMonitor(request: RemoteMonitorUpsertRequest): Result<RemoteMonitorMutationResult> = operation { readOnly() }
    suspend fun updateRemoteMonitor(id: String, request: RemoteMonitorUpsertRequest): Result<RemoteMonitorMutationResult> = operation { readOnly() }
    suspend fun deleteRemoteMonitor(id: String): Result<RemoteMonitorDeleteResult> = operation { readOnly() }
    suspend fun copyRemoteMonitor(id: String): Result<RemoteMonitorMutationResult> = operation { readOnly() }
    suspend fun reorderRemoteMonitors(expectedMonitorIds: List<String>, monitorIds: List<String>): Result<RemoteMonitorConfigsResult> = operation { readOnly() }

    suspend fun sendAlarm(message: String): Result<Unit> = operation {
        synchronized(lock) {
            require(config.value.enabled) { "请先启用本机监控" }
            persist(pending = _pendingEvents.value + event(null, RelayAlertType.MANUAL, message.take(500)))
        }
    }
    private fun event(monitor: RemoteMonitorConfig?, type: RelayAlertType, message: String) = RelayAlarmEvent(
        System.currentTimeMillis(), type, "local", "local", "本机", message, System.currentTimeMillis(),
        monitor?.id, monitor?.name, UUID.randomUUID().toString())

    suspend fun testRemoteMonitor(id: String): Result<RemoteMonitorTestResult> = operation { readOnly() }
    suspend fun refreshRemoteMonitorValues(): Result<RemoteMonitorRefreshResult> = operation {
        checkMutex.withLock { syncBalances(config.value.enabled) }
    }
    suspend fun checkDueMonitors(): Result<RemoteMonitorRefreshResult> = operation {
        checkMutex.withLock {
            if (System.currentTimeMillis() < nextSyncAt || !config.value.enabled) {
                RemoteMonitorRefreshResult(true, System.currentTimeMillis(), 0, 0, 0, emptyList())
            } else syncBalances(true)
        }
    }

    private fun syncBalances(allowAlarm: Boolean): RemoteMonitorRefreshResult {
        check(loadError.isEmpty()) { loadError }
        val requestConfig = config.value
        require(requestConfig.isComplete) { "请配置 sub2api 地址和余额查询令牌" }
        val generation = synchronized(lock) { configGeneration }
        val now = System.currentTimeMillis()
        // A connection failure must back off too, otherwise the service loop retries each second.
        nextSyncAt = now + 60_000
        try {
            val snapshot = client.fetch(requestConfig)
            synchronized(lock) {
                if (generation != configGeneration) return RemoteMonitorRefreshResult(true, now, 0, 0, 0, emptyList())
                val nextStates = mutableMapOf<String, LocalMonitorState>()
                var pending = _pendingEvents.value.filter { event -> event.monitorId == null || snapshot.monitors.any { it.config.id == event.monitorId && it.config.enabled } }
                val results = snapshot.monitors.map { item ->
                    val monitor = item.config
                    val previous = states[monitor.id] ?: LocalMonitorState()
                    // Cached snapshots are not new checks: never inflate failure counts or repeat alarms.
                    val checkedAt = item.checkedAt
                    val next = if (checkedAt > previous.checkedAt && monitor.enabled) {
                        val (updated, alarm) = previous.checked(monitor, item.result, checkedAt,
                            allowAlarm && config.value.enabled)
                        if (alarm != null && pending.none { it.monitorId == monitor.id && it.type == alarm }) {
                            pending = pending + event(monitor, alarm, if (alarm == RelayAlertType.MONITOR_BREACH)
                                "当前余额 ${updated.value}，报警条件 ${monitor.operator} ${monitor.threshold}"
                                else "连续 ${updated.errors} 次查询失败：${updated.error}")
                        }
                        updated
                    } else previous
                    nextStates[monitor.id] = next
                    val value = item.result.getOrNull()
                    RemoteMonitorRefreshItemResult(monitor.id, monitor.name, item.result.isSuccess,
                        value?.let { RemoteMonitorRefreshCheckResult(it.httpCode, it.rawValue, it.value, it.breached) },
                        item.result.exceptionOrNull()?.let { RemoteMonitorRefreshError("CHECK_FAILED", it.message.orEmpty(), null) })
                }
                persist(snapshot.monitors.map { it.config }, nextStates, pending)
                check(preferences.edit().remove("local_document_v1").commit()) { "无法清除旧本地凭据" }
                nextSyncAt = now + snapshot.intervalSeconds * 1000
                _monitorConfigStatus.value = RemoteMonitorConfigStatus(true, now, "")
                _status.value = RelayStatus(if (config.value.enabled) RelayConnectionState.ONLINE else RelayConnectionState.DISABLED,
                    lastCheckedAt = now, lastOnlineAt = now)
                return RemoteMonitorRefreshResult(results.all { it.ok }, now, results.size,
                    results.count { it.ok }, results.count { !it.ok }, results)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (generation == configGeneration) {
                    _status.value = RelayStatus(RelayConnectionState.OFFLINE, lastCheckedAt = now,
                        lastOnlineAt = _status.value.lastOnlineAt, lastError = error.message.orEmpty())
                    _monitorConfigStatus.value = RemoteMonitorConfigStatus(false, _monitorConfigStatus.value.lastLoadedAt, error.message.orEmpty())
                }
            }
            throw error
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    private fun <T> readArray(array: JSONArray?, parse: (JSONObject) -> T): List<T> =
        if (array == null) emptyList() else (0 until array.length()).map { parse(array.getJSONObject(it)) }

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
        return run {
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
        }
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


    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "credit_inform_local_monitor"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        @Volatile private var instance: RelayRepository? = null
        fun get(context: Context): RelayRepository = instance ?: synchronized(this) {
            instance ?: RelayRepository(context.applicationContext).also { instance = it }
        }
    }
}
