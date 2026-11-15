package com.creditinform.app.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.creditinform.app.network.LocalMonitorClient
import com.creditinform.app.network.RelayApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences("credit_inform_relay", Context.MODE_PRIVATE)
    private val lock = Any()
    private val checkMutex = Mutex()
    private var configGeneration = 0L
    private val client = LocalMonitorClient()
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
        // Overwrite old connection settings without reading or using the old server credentials.
        saveConfig(_config.value)
    }

    private fun loadDocument(): JSONObject = try {
        val document = preferences.getString("local_document_v1", null)?.let { JSONObject(decryptSecret(it)) } ?: JSONObject()
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
        return RelayConfig(enabled = j.optBoolean("enabled"), soundEnabled = j.optBoolean("soundEnabled", true),
            vibrateEnabled = j.optBoolean("vibrateEnabled", true), alarmVolumePercent = j.optInt("alarmVolumePercent", 100),
            ringtoneUri = j.optString("ringtoneUri")).normalized()
    }

    fun saveConfig(value: RelayConfig) = synchronized(lock) {
        val normalized = value.normalized()
        val j = JSONObject().put("enabled", normalized.enabled).put("soundEnabled", normalized.soundEnabled)
            .put("vibrateEnabled", normalized.vibrateEnabled).put("alarmVolumePercent", normalized.alarmVolumePercent)
            .put("ringtoneUri", normalized.ringtoneUri)
        check(preferences.edit().putString("config", j.toString()).commit()) { "无法保存本机设置或参数无效" }
        _config.value = normalized
        configGeneration++
        _status.value = RelayStatus(if (normalized.enabled) RelayConnectionState.CHECKING else RelayConnectionState.DISABLED)
    }

    fun setEnabled(enabled: Boolean) = saveConfig(config.value.copy(enabled = enabled))

    suspend fun importMonitorConfig(uri: Uri): Result<Int> = operation {
        val text = appContext.contentResolver.openInputStream(uri)?.use(MonitorConfigImport::read)
            ?: error("无法打开所选配置文件")
        val imported = MonitorConfigImport.parse(text)
        // Wait for the current batch, then replace all data in one durable write. No old
        // queued checks can publish after the replacement or sound an obsolete alarm.
        checkMutex.withLock {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                synchronized(lock) {
                    persist(imported, emptyMap(), emptyList())
                    configGeneration++
                }
                com.creditinform.app.monitoring.AlarmController.dismissAll(appContext)
            }
        }
        imported.size
    }

    private fun persist(monitors: List<RemoteMonitorConfig> = _remoteMonitorConfigs.value,
                        nextStates: Map<String, LocalMonitorState> = states,
                        pending: List<RelayAlarmEvent> = _pendingEvents.value) {
        check(loadError.isEmpty()) { loadError }
        val document = JSONObject().put("monitors", JSONArray().apply { monitors.forEach { put(it.toLocalJson()) } })
            .put("states", JSONObject().apply { nextStates.forEach { (id, state) -> put(id, state.toJson()) } })
            .put("pending", JSONArray().apply { pending.forEach { put(it.toJson()) } })
        check(preferences.edit().putString("local_document_v1", encryptSecret(document.toString())).commit()) { "无法保存本机监控数据" }
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

    suspend fun testConnection(): Result<Unit> = operation { check(loadError.isEmpty()) { loadError } }
    suspend fun refreshMonitors(): Result<RemoteMonitorsResult> = operation {
        RemoteMonitorsResult(System.currentTimeMillis(), _monitorConfigStatus.value, _monitorStatuses.value)
    }
    suspend fun listRemoteMonitorConfigs(): Result<RemoteMonitorConfigsResult> = operation {
        check(loadError.isEmpty()) { loadError }; configsResult()
    }
    private fun configsResult() = RemoteMonitorConfigsResult(System.currentTimeMillis(), "", _remoteMonitorConfigs.value)

    suspend fun createRemoteMonitor(request: RemoteMonitorUpsertRequest) = saveMonitor(null, request)
    suspend fun updateRemoteMonitor(id: String, request: RemoteMonitorUpsertRequest) = saveMonitor(id, request)
    private suspend fun saveMonitor(id: String?, request: RemoteMonitorUpsertRequest): Result<RemoteMonitorMutationResult> = operation {
        synchronized(lock) {
            validateMonitorRequest(request)
            val old = id?.let { key -> _remoteMonitorConfigs.value.firstOrNull { it.id == key } ?: error("监控不存在") }
            if (old != null && request.revision != null && old.revision != request.revision) throw RelayApiException(409, "配置已更新，请重新打开编辑")
            val body = if (request.bodySpecified) request.body else old?.body
            val monitor = RemoteMonitorConfig("local", id ?: UUID.randomUUID().toString(), (old?.revision ?: 0) + 1,
                System.currentTimeMillis(), request.name, request.url, request.method, request.headersJson ?: old?.headersJson,
                body, headersConfigured = !request.headersJson.isNullOrBlank(), bodyConfigured = body != null,
                jsonPath = request.jsonPath, operator = request.operator, threshold = request.threshold,
                multiplier = request.multiplier, offset = request.offset, pollIntervalSeconds = request.pollIntervalSeconds,
                timeoutSeconds = request.timeoutSeconds, errorThreshold = request.errorThreshold, repeatAlertSeconds = request.repeatAlertSeconds,
                enabled = request.enabled, provider = request.provider, gatewayAccessKey = request.gatewayAccessKey,
                gatewaySecretKey = request.gatewaySecretKey)
            val monitors = if (old == null) _remoteMonitorConfigs.value + monitor else _remoteMonitorConfigs.value.map { if (it.id == id) monitor else it }
            persist(monitors, states - monitor.id)
            RemoteMonitorMutationResult(System.currentTimeMillis(), "", monitor)
        }
    }

    suspend fun deleteRemoteMonitor(id: String): Result<RemoteMonitorDeleteResult> = operation {
        synchronized(lock) {
            persist(_remoteMonitorConfigs.value.filterNot { it.id == id }, states - id)
            RemoteMonitorDeleteResult(System.currentTimeMillis())
        }
    }
    suspend fun copyRemoteMonitor(id: String): Result<RemoteMonitorMutationResult> = operation {
        synchronized(lock) {
            val list = _remoteMonitorConfigs.value.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            require(index >= 0) { "监控不存在" }
            val copy = list[index].copy(id = UUID.randomUUID().toString(), name = list[index].name.take(120) + " 副本", revision = 1)
            list.add(index + 1, copy)
            persist(list)
            RemoteMonitorMutationResult(System.currentTimeMillis(), "", copy)
        }
    }
    suspend fun reorderRemoteMonitors(expectedMonitorIds: List<String>, monitorIds: List<String>): Result<RemoteMonitorConfigsResult> = operation {
        synchronized(lock) {
            val list = _remoteMonitorConfigs.value
            require(expectedMonitorIds == list.map { it.id } && monitorIds.size == list.size && monitorIds.toSet() == expectedMonitorIds.toSet()) { "监控列表已变更或检测参数无效" }
            persist(monitorIds.map { id -> list.first { it.id == id } })
            configsResult()
        }
    }

    suspend fun sendAlarm(message: String): Result<Unit> = operation {
        synchronized(lock) {
            require(config.value.enabled) { "请先启用本机监控" }
            persist(pending = _pendingEvents.value + event(null, RelayAlertType.MANUAL, message.take(500)))
        }
    }
    private fun event(monitor: RemoteMonitorConfig?, type: RelayAlertType, message: String) = RelayAlarmEvent(
        System.currentTimeMillis(), type, "local", "local", "本机", message, System.currentTimeMillis(),
        monitor?.id, monitor?.name, UUID.randomUUID().toString())

    suspend fun testRemoteMonitor(id: String): Result<RemoteMonitorTestResult> = operation {
        checkMutex.withLock {
            val monitor = _remoteMonitorConfigs.value.firstOrNull { it.id == id } ?: error("监控不存在")
            checkOne(monitor, false).getOrThrow()
        }
    }
    suspend fun refreshRemoteMonitorValues(): Result<RemoteMonitorRefreshResult> = operation {
        checkMutex.withLock { checkAll(false) }
    }
    suspend fun checkDueMonitors(): Result<RemoteMonitorRefreshResult> = operation {
        checkMutex.withLock { checkAll(true) }
    }
    private suspend fun checkAll(dueOnly: Boolean): RemoteMonitorRefreshResult = coroutineScope {
        check(loadError.isEmpty()) { loadError }
        val now = System.currentTimeMillis()
        val monitors = synchronized(lock) {
            _remoteMonitorConfigs.value.filter { it.enabled && (!dueOnly || (config.value.enabled && (states[it.id]?.nextAt ?: 0) <= now)) }
        }
        val semaphore = Semaphore(4)
        val results = monitors.map { monitor -> async {
            semaphore.withPermit {
                val result = checkOne(monitor, true)
                val value = result.getOrNull()
                RemoteMonitorRefreshItemResult(monitor.id, monitor.name, result.isSuccess,
                    value?.let { RemoteMonitorRefreshCheckResult(it.httpCode, it.rawValue, it.value, it.breached) },
                    result.exceptionOrNull()?.let { RemoteMonitorRefreshError("CHECK_FAILED", it.message.orEmpty(), (it as? RelayApiException)?.httpCode) })
            }
        } }.awaitAll()
        _status.value = RelayStatus(if (config.value.enabled) RelayConnectionState.ONLINE else RelayConnectionState.DISABLED,
            lastCheckedAt = System.currentTimeMillis())
        RemoteMonitorRefreshResult(results.all { it.ok }, System.currentTimeMillis(), results.size, results.count { it.ok }, results.count { !it.ok }, results)
    }
    private fun checkOne(monitor: RemoteMonitorConfig, allowAlarm: Boolean): Result<RemoteMonitorTestResult> {
        val generation = synchronized(lock) { configGeneration }
        val result = runCatching { client.check(monitor) }
        synchronized(lock) {
            // Editing, deleting or disabling during a request invalidates its result.
            if (generation != configGeneration || _remoteMonitorConfigs.value.firstOrNull { it.id == monitor.id } != monitor) return result
            val previous = states[monitor.id] ?: LocalMonitorState()
            val (next, alarm) = previous.checked(monitor, result, System.currentTimeMillis(), allowAlarm && config.value.enabled && monitor.enabled)
            val pending = if (alarm == null || _pendingEvents.value.any { it.monitorId == monitor.id && it.type == alarm }) _pendingEvents.value
                else _pendingEvents.value + event(monitor, alarm, if (alarm == RelayAlertType.MONITOR_BREACH) "当前值 ${next.value}，报警条件 ${monitor.operator} ${monitor.threshold}" else "连续 ${next.errors} 次检测失败：${next.error}")
            persist(nextStates = states + (monitor.id to next), pending = pending)
        }
        return result
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
