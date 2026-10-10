package com.creditinform.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.creditinform.app.data.RelayConfig
import com.creditinform.app.data.RelayConnectionState
import com.creditinform.app.data.RelayRepository
import com.creditinform.app.data.RelayStatus
import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorConfigStatus
import com.creditinform.app.data.RemoteMonitorStatus
import com.creditinform.app.monitoring.AlarmController
import com.creditinform.app.monitoring.NotificationChannels
import com.creditinform.app.monitoring.RelayService
import com.creditinform.app.monitoring.UrgentAlarm
import com.creditinform.app.network.RelayApiException
import com.creditinform.app.ui.CreditInformTheme
import com.creditinform.app.ui.MonitorValueTone
import com.creditinform.app.ui.RemoteMonitorEditorScreen
import com.creditinform.app.ui.RemoteMonitorRow
import com.creditinform.app.ui.mergeRemoteMonitorRows
import com.creditinform.app.ui.monitorTestErrorMessage
import com.creditinform.app.ui.monitorValueTone
import com.creditinform.app.ui.moveRemoteMonitorId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

private const val MONITOR_ITEM_KEY_PREFIX = "monitor:"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.create(this)
        AlarmController.restoreIfNeeded(this)
        runCatching { RelayService.sync(this) }
        setContent { CreditInformTheme { RelayAlarmApp() } }
    }
}

@Composable
private fun RelayAlarmApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { RelayRepository.get(context) }
    val config by repository.config.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val monitors by repository.monitorStatuses.collectAsStateWithLifecycle()
    val monitorConfigStatus by repository.monitorConfigStatus.collectAsStateWithLifecycle()
    val remoteMonitorConfigs by repository.remoteMonitorConfigs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshProgressText by remember { mutableStateOf("") }
    var adminLoading by remember { mutableStateOf(false) }
    var adminError by remember { mutableStateOf("") }
    var adminActionId by remember { mutableStateOf<String?>(null) }
    var copyActionId by remember { mutableStateOf<String?>(null) }
    var reordering by remember { mutableStateOf(false) }
    var showMonitorEditor by remember { mutableStateOf(false) }
    var editingMonitor by remember { mutableStateOf<RemoteMonitorConfig?>(null) }
    var editorSaving by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RemoteMonitorConfig?>(null) }
    var deleteError by remember { mutableStateOf("") }
    var showConfiguration by rememberSaveable { mutableStateOf(false) }
    var showSendConfirmation by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (repository.config.value.enabled) {
            testing = true
            repository.testConnection().onSuccess {
                refreshing = true
                repository.refreshMonitors()
                refreshing = false
            }
            testing = false
        }
    }

    LaunchedEffect(config.serverUrl, config.adminToken) {
        adminError = ""
        if (config.adminToken.isNotBlank()) {
            adminLoading = true
            repository.listRemoteMonitorConfigs().onFailure { error ->
                adminError = "加载监控配置失败：${error.readableMessage()}"
            }
            adminLoading = false
        }
    }

    if (showMonitorEditor) {
        RemoteMonitorEditorScreen(
            existing = editingMonitor,
            saving = editorSaving,
            externalError = editorError,
            onCancel = {
                showMonitorEditor = false
                editingMonitor = null
                editorError = ""
            },
            onSave = { request ->
                scope.launch {
                    editorSaving = true
                    editorError = ""
                    val target = editingMonitor
                    val result = if (target == null) {
                        repository.createRemoteMonitor(request)
                    } else {
                        repository.updateRemoteMonitor(target.id, request)
                    }
                    result.onSuccess {
                        showMonitorEditor = false
                        editingMonitor = null
                        Toast.makeText(context, "监控配置已保存", Toast.LENGTH_SHORT).show()
                        adminLoading = true
                        adminError = ""
                        repository.listRemoteMonitorConfigs().onFailure { error ->
                            adminError = "重新加载配置失败：${error.readableMessage()}"
                        }
                        repository.refreshMonitors().onFailure { error ->
                            val message = "刷新运行状态失败：${error.readableMessage()}"
                            adminError = listOf(adminError, message)
                                .filter(String::isNotBlank)
                                .joinToString("\n")
                        }
                        adminLoading = false
                    }.onFailure { error ->
                        if ((error as? RelayApiException)?.httpCode == 409) {
                            showMonitorEditor = false
                            editingMonitor = null
                            adminLoading = true
                            val reloadResult = repository.listRemoteMonitorConfigs()
                            adminLoading = false
                            adminError = if (reloadResult.isSuccess) {
                                "配置已被其他客户端修改，已重新加载，请重新编辑"
                            } else {
                                "配置版本冲突，重新加载失败：${reloadResult.exceptionOrNull()?.readableMessage()}"
                            }
                            Toast.makeText(context, adminError, Toast.LENGTH_LONG).show()
                        } else {
                            editorError = "保存失败：${error.readableMessage()}"
                        }
                    }
                    editorSaving = false
                }
            }
        )
    } else {
        RelayHomeScreen(
            config = config,
            status = status,
            monitors = monitors,
            monitorConfigStatus = monitorConfigStatus,
            remoteMonitorConfigs = remoteMonitorConfigs,
            testing = testing,
            sending = sending,
            refreshing = refreshing,
            refreshProgressText = refreshProgressText,
            adminLoading = adminLoading,
            adminError = adminError,
            adminActionId = adminActionId,
            copyActionId = copyActionId,
            reordering = reordering,
            permissionRevision = permissionRevision,
            onOpenConfiguration = { showConfiguration = true },
            onTestConnection = {
                scope.launch {
                    testing = true
                    val connectionResult = repository.testConnection()
                    connectionResult
                        .onSuccess { health ->
                            refreshing = true
                            val syncErrors = mutableListOf<String>()
                            repository.refreshMonitors().onFailure { error ->
                                syncErrors += "同步监控值失败：${error.readableMessage()}"
                            }
                            if (config.hasAdminAccess) {
                                adminLoading = true
                                repository.listRemoteMonitorConfigs().onFailure { error ->
                                    syncErrors += "同步监控配置失败：${error.readableMessage()}"
                                }
                                adminLoading = false
                            }
                            refreshing = false
                            adminError = syncErrors.joinToString("\n")
                            Toast.makeText(
                                context,
                                if (syncErrors.isEmpty()) {
                                    "连接成功，已同步最新值，在线客户端 ${health.onlineClients} 个"
                                } else {
                                    "连接成功，但部分监控数据同步失败"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                "连接失败：${error.readableMessage()}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    testing = false
                }
            },
            onRequestSendAlarm = { showSendConfirmation = true },
            onTestLocalAlarm = {
                AlarmController.triggerUrgent(
                    context,
                    UrgentAlarm(
                        id = "local-test",
                        title = "本机报警测试",
                        message = "如果能听到铃声并感到震动，报警输出工作正常",
                        detail = "请点击下方按钮结束测试",
                        soundEnabled = config.soundEnabled,
                        vibrateEnabled = config.vibrateEnabled,
                        alarmVolumePercent = config.alarmVolumePercent,
                        ringtoneUri = config.ringtoneUri
                    )
                )
            },
            onRefreshMonitors = {
                scope.launch {
                    val isAdmin = config.hasAdminAccess
                    refreshing = true
                    adminLoading = isAdmin
                    adminError = ""
                    val errors = mutableListOf<String>()
                    try {
                        if (isAdmin) {
                            refreshProgressText = "正在检测并同步全部已启用监控…"
                            repository.refreshRemoteMonitorValues()
                                .onSuccess { result ->
                                    if (result.failed > 0) {
                                        errors += "部分检测失败：${result.failed}/${result.total} 项"
                                    }
                                }
                                .onFailure { error ->
                                    errors += "检测刷新失败：${error.readableMessage()}"
                                }
                            refreshProgressText = "正在同步监控配置…"
                            repository.listRemoteMonitorConfigs().onFailure { error ->
                                errors += "配置同步失败：${error.readableMessage()}"
                            }
                        } else {
                            refreshProgressText = "正在同步最新监控值…"
                            repository.refreshMonitors().onFailure { error ->
                                errors += "刷新运行状态失败：${error.readableMessage()}"
                            }
                        }
                        adminError = errors.joinToString("\n")
                    } finally {
                        refreshProgressText = ""
                        adminLoading = false
                        refreshing = false
                    }
                }
            },
            onCreateMonitor = {
                editingMonitor = null
                editorError = ""
                showMonitorEditor = true
            },
            onEditMonitor = { monitor ->
                editingMonitor = monitor
                editorError = ""
                showMonitorEditor = true
            },
            onDeleteMonitor = { monitor ->
                deleteTarget = monitor
                deleteError = ""
            },
            onCopyMonitor = { monitor ->
                scope.launch {
                    copyActionId = monitor.id
                    adminError = ""
                    repository.copyRemoteMonitor(monitor.id)
                        .onSuccess {
                            Toast.makeText(
                                context,
                                "已复制，副本默认停用",
                                Toast.LENGTH_SHORT
                            ).show()
                            repository.refreshMonitors().onFailure { error ->
                                adminError = "刷新运行状态失败：${error.readableMessage()}"
                            }
                        }
                        .onFailure { error ->
                            adminError = "复制失败：${error.readableMessage()}"
                            Toast.makeText(context, adminError, Toast.LENGTH_LONG).show()
                        }
                    copyActionId = null
                }
            },
            onReorderMonitors = { expectedIds, orderedIds, onComplete ->
                scope.launch {
                    reordering = true
                    adminError = ""
                    repository.reorderRemoteMonitors(expectedIds, orderedIds)
                        .onSuccess {
                            onComplete(true)
                        }
                        .onFailure { error ->
                            adminError = "保存排序失败：${error.readableMessage()}"
                            onComplete(false)
                            repository.listRemoteMonitorConfigs()
                            Toast.makeText(context, adminError, Toast.LENGTH_LONG).show()
                        }
                    reordering = false
                }
            },
            onTestMonitor = { monitor ->
                scope.launch {
                    adminActionId = monitor.id
                    adminError = ""
                    val testResult = repository.testRemoteMonitor(monitor.id)
                    val refreshResult = repository.refreshMonitors()
                    adminError = monitorTestErrorMessage(
                        monitorName = monitor.name,
                        testError = testResult.exceptionOrNull()?.readableMessage(),
                        refreshError = refreshResult.exceptionOrNull()?.readableMessage()
                    )
                    Toast.makeText(
                        context,
                        adminError.ifBlank { "${monitor.name} 测试完成" },
                        if (adminError.isBlank()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                    adminActionId = null
                }
            }
        )
    }

    if (!showMonitorEditor && showConfiguration) {
        RelayConfigurationDialog(
            current = config,
            busy = testing,
            onDismiss = { showConfiguration = false },
            onSaveAndTest = { candidate ->
                showConfiguration = false
                repository.saveConfig(candidate)
                runCatching { RelayService.sync(context) }
                if (candidate.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                scope.launch {
                    testing = true
                    repository.testConnection()
                        .onSuccess {
                            Toast.makeText(
                                context,
                                "配置已保存，服务端连接正常",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (repository.config.value.enabled) {
                                refreshing = true
                                repository.refreshMonitors()
                                refreshing = false
                            }
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                "配置已保存，连接检测失败：${error.readableMessage()}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    testing = false
                }
            }
        )
    }

    if (!showMonitorEditor && showSendConfirmation) {
        SendAlarmDialog(
            sending = sending,
            onDismiss = { showSendConfirmation = false },
            onConfirm = { message ->
                scope.launch {
                    sending = true
                    repository.sendAlarm(message)
                        .onSuccess { result ->
                            showSendConfirmation = false
                            Toast.makeText(
                                context,
                                buildString {
                                    append("服务端已接收报警，当前在线接收端 ")
                                    append(result.onlineRecipients)
                                    append(" 个")
                                    if (result.recipients > result.onlineRecipients) {
                                        append("（已注册 ").append(result.recipients).append(" 个）")
                                    }
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                "发送失败：${error.readableMessage()}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    sending = false
                }
            }
        )
    }

    deleteTarget?.let { monitor ->
        DeleteRemoteMonitorDialog(
            monitor = monitor,
            deleting = adminActionId == monitor.id,
            error = deleteError,
            onDismiss = {
                if (adminActionId == null) {
                    deleteTarget = null
                    deleteError = ""
                }
            },
            onConfirm = {
                scope.launch {
                    adminActionId = monitor.id
                    deleteError = ""
                    repository.deleteRemoteMonitor(monitor.id)
                        .onSuccess {
                            deleteTarget = null
                            Toast.makeText(context, "监控配置已删除", Toast.LENGTH_SHORT).show()
                            adminLoading = true
                            adminError = ""
                            repository.listRemoteMonitorConfigs().onFailure { error ->
                                adminError = "重新加载配置失败：${error.readableMessage()}"
                            }
                            repository.refreshMonitors().onFailure { error ->
                                val message = "刷新运行状态失败：${error.readableMessage()}"
                                adminError = listOf(adminError, message)
                                    .filter(String::isNotBlank)
                                    .joinToString("\n")
                            }
                            adminLoading = false
                        }
                        .onFailure { error ->
                            deleteError = "删除失败：${error.readableMessage()}"
                        }
                    adminActionId = null
                }
            }
        )
    }

}

@Composable
private fun RelayHomeScreen(
    config: RelayConfig,
    status: RelayStatus,
    monitors: List<RemoteMonitorStatus>,
    monitorConfigStatus: RemoteMonitorConfigStatus,
    remoteMonitorConfigs: List<RemoteMonitorConfig>,
    testing: Boolean,
    sending: Boolean,
    refreshing: Boolean,
    refreshProgressText: String,
    adminLoading: Boolean,
    adminError: String,
    adminActionId: String?,
    copyActionId: String?,
    reordering: Boolean,
    permissionRevision: Int,
    onOpenConfiguration: () -> Unit,
    onTestConnection: () -> Unit,
    onRequestSendAlarm: () -> Unit,
    onTestLocalAlarm: () -> Unit,
    onRefreshMonitors: () -> Unit,
    onCreateMonitor: () -> Unit,
    onEditMonitor: (RemoteMonitorConfig) -> Unit,
    onDeleteMonitor: (RemoteMonitorConfig) -> Unit,
    onCopyMonitor: (RemoteMonitorConfig) -> Unit,
    onReorderMonitors: (List<String>, List<String>, (Boolean) -> Unit) -> Unit,
    onTestMonitor: (RemoteMonitorConfig) -> Unit
) {
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val configuredIds = remoteMonitorConfigs.map(RemoteMonitorConfig::id)
    val orderedIds = remember {
        mutableStateListOf<String>().apply { addAll(configuredIds) }
    }
    var draggedMonitorId by remember { mutableStateOf<String?>(null) }
    var draggedDistance by remember { mutableFloatStateOf(0f) }
    var orderBeforeDrag by remember { mutableStateOf<List<String>>(emptyList()) }
    val autoScroll = remember { DragAutoScrollState() }
    val edgeScrollThreshold = with(LocalDensity.current) { 72.dp.toPx() }

    LaunchedEffect(configuredIds) {
        if (draggedMonitorId == null && orderedIds.toList() != configuredIds) {
            orderedIds.clear()
            orderedIds.addAll(configuredIds)
        }
    }

    val configsById = remoteMonitorConfigs.associateBy(RemoteMonitorConfig::id)
    val effectiveOrder = orderedIds.filter { it in configsById } +
        configuredIds.filterNot { it in orderedIds }
    val orderedConfigs = effectiveOrder.mapNotNull(configsById::get)
    val monitorRows = mergeRemoteMonitorRows(orderedConfigs, monitors)

    fun reorderVisibleMonitor(draggedId: String) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.firstOrNull {
            it.key == "$MONITOR_ITEM_KEY_PREFIX$draggedId"
        } ?: return
        val draggedCenter = draggedItem.offset + draggedDistance + draggedItem.size / 2f
        val currentOrderIndex = orderedIds.indexOf(draggedId)
        if (currentOrderIndex < 0) return
        val candidates = visibleItems.mapNotNull { item ->
            val key = item.key as? String ?: return@mapNotNull null
            if (!key.startsWith(MONITOR_ITEM_KEY_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(MONITOR_ITEM_KEY_PREFIX)
            val orderIndex = orderedIds.indexOf(id)
            if (id == draggedId || orderIndex < 0) return@mapNotNull null
            DragTarget(item, id, orderIndex, item.offset + item.size / 2f)
        }
        val target = candidates
            .filter { it.orderIndex > currentOrderIndex && draggedCenter > it.center }
            .maxByOrNull(DragTarget::orderIndex)
            ?: candidates
                .filter { it.orderIndex < currentOrderIndex && draggedCenter < it.center }
                .minByOrNull(DragTarget::orderIndex)
            ?: return
        val reordered = moveRemoteMonitorId(orderedIds, draggedId, target.id)
        if (reordered != orderedIds) {
            orderedIds.clear()
            orderedIds.addAll(reordered)
            draggedDistance += draggedItem.offset - target.item.offset
        }
    }

    fun edgeScrollSpeed(draggedId: String): Float {
        val draggedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key == "$MONITOR_ITEM_KEY_PREFIX$draggedId"
        } ?: return 0f
        val draggedTop = draggedItem.offset + draggedDistance
        val draggedBottom = draggedTop + draggedItem.size
        val viewportStart = listState.layoutInfo.viewportStartOffset
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        return when {
            draggedTop < viewportStart + edgeScrollThreshold ->
                (draggedTop - viewportStart - edgeScrollThreshold).coerceAtLeast(-32f)
            draggedBottom > viewportEnd - edgeScrollThreshold ->
                (draggedBottom - viewportEnd + edgeScrollThreshold).coerceAtMost(32f)
            else -> 0f
        }
    }

    fun stopAutoScroll() {
        autoScroll.speed = 0f
        autoScroll.job?.cancel()
        autoScroll.job = null
    }

    Scaffold { scaffoldPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "值守报警",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                RelayConnectionCard(
                    config,
                    status,
                    testing,
                    onOpenConfiguration,
                    onTestConnection
                )
            }
            item {
                ManualAlarmCard(config, sending, onRequestSendAlarm, onTestLocalAlarm)
            }
            item { PermissionChecklist(permissionRevision) }
            item {
                RemoteMonitorHeader(
                    count = monitorRows.size,
                    loading = refreshing || adminLoading || reordering,
                    canRefresh = config.enabled && config.isCompleteForUi(),
                    canManage = config.hasAdminAccess,
                    onRefresh = onRefreshMonitors,
                    onCreate = onCreateMonitor
                )
            }
            if (refreshing || adminLoading || reordering) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (refreshProgressText.isNotBlank()) {
                            Text(
                                refreshProgressText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
            if (monitorConfigStatus.lastError.isNotBlank()) {
                item {
                    Text(
                        "服务端监控配置异常：${monitorConfigStatus.lastError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (adminError.isNotBlank()) {
                item {
                    Text(
                        adminError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (monitorRows.isEmpty() && !refreshing && !adminLoading) {
                item { EmptyMonitorState(config, monitorConfigStatus) }
            } else {
                items(monitorRows, key = { "$MONITOR_ITEM_KEY_PREFIX${it.id}" }) { row ->
                    val monitorConfig = row.config
                    val showDragHandle = config.hasAdminAccess && monitorConfig != null &&
                        orderedConfigs.size > 1
                    val canDrag = showDragHandle &&
                        !adminLoading && adminActionId == null && copyActionId == null && !reordering
                        && (draggedMonitorId == row.id ||
                            (draggedMonitorId == null && orderedIds.toList() == configuredIds))
                    UnifiedRemoteMonitorCard(
                        row = row,
                        canManage = config.hasAdminAccess && monitorConfig != null,
                        showDragHandle = showDragHandle,
                        canDrag = canDrag,
                        actionInProgress = adminLoading || adminActionId != null ||
                            copyActionId != null || reordering,
                        testing = adminActionId == row.id,
                        copying = copyActionId == row.id,
                        isDragging = draggedMonitorId == row.id,
                        dragOffset = if (draggedMonitorId == row.id) draggedDistance else 0f,
                        onEdit = { monitorConfig?.let(onEditMonitor) },
                        onDelete = { monitorConfig?.let(onDeleteMonitor) },
                        onCopy = { monitorConfig?.let(onCopyMonitor) },
                        onTest = { monitorConfig?.let(onTestMonitor) },
                        onDragStart = {
                            orderBeforeDrag = orderedIds.toList()
                            draggedMonitorId = row.id
                            draggedDistance = 0f
                        },
                        onDrag = { delta ->
                            if (draggedMonitorId == row.id) {
                                draggedDistance += delta
                                reorderVisibleMonitor(row.id)
                                autoScroll.speed = edgeScrollSpeed(row.id)
                                if (autoScroll.speed != 0f && autoScroll.job?.isActive != true) {
                                    val job = dragScope.launch {
                                        while (isActive && draggedMonitorId == row.id &&
                                            autoScroll.speed != 0f
                                        ) {
                                            val consumed = listState.scrollBy(autoScroll.speed)
                                            if (consumed == 0f) break
                                            draggedDistance += consumed
                                            reorderVisibleMonitor(row.id)
                                            autoScroll.speed = edgeScrollSpeed(row.id)
                                            delay(16)
                                        }
                                    }
                                    autoScroll.job = job
                                    job.invokeOnCompletion {
                                        if (autoScroll.job === job) autoScroll.job = null
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            stopAutoScroll()
                            val expectedIds = orderBeforeDrag
                            val updatedIds = orderedIds.toList()
                            draggedMonitorId = null
                            draggedDistance = 0f
                            if (expectedIds.isNotEmpty() && expectedIds != updatedIds) {
                                onReorderMonitors(expectedIds, updatedIds) { saved ->
                                    if (!saved) {
                                        orderedIds.clear()
                                        orderedIds.addAll(expectedIds)
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            stopAutoScroll()
                            orderedIds.clear()
                            orderedIds.addAll(orderBeforeDrag)
                            draggedMonitorId = null
                            draggedDistance = 0f
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayConnectionCard(
    config: RelayConfig,
    status: RelayStatus,
    testing: Boolean,
    onOpenConfiguration: () -> Unit,
    onTestConnection: () -> Unit
) {
    val presentation = relayStatusPresentation(config, status, testing)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = presentation.containerColor())
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).background(presentation.indicatorColor(), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        presentation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        presentation.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenConfiguration,
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("连接配置") }
                Button(
                    onClick = onTestConnection,
                    enabled = config.isCompleteForUi() && !testing,
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(if (testing) "检测中" else "检测连接") }
            }
        }
    }
}

@Composable
private fun ManualAlarmCard(
    config: RelayConfig,
    sending: Boolean,
    onRequestSendAlarm: () -> Unit,
    onTestLocalAlarm: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "手动报警",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRequestSendAlarm,
                    enabled = config.enabled && config.isCompleteForUi() && !sending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f).height(44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(if (sending) "发送中" else "发送报警", maxLines = 1) }
                OutlinedButton(
                    onClick = onTestLocalAlarm,
                    modifier = Modifier.weight(1f).height(44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("测试本机", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun RemoteMonitorHeader(
    count: Int,
    loading: Boolean,
    canRefresh: Boolean,
    canManage: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "服务端监控与配置 ($count)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (canManage) {
            IconButton(onClick = onCreate, enabled = !loading) {
                Icon(Icons.Filled.Add, contentDescription = "新增服务端监控")
            }
        }
        IconButton(onClick = onRefresh, enabled = canRefresh && !loading) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新服务端监控")
        }
    }
}

@Composable
private fun UnifiedRemoteMonitorCard(
    row: RemoteMonitorRow,
    canManage: Boolean,
    showDragHandle: Boolean,
    canDrag: Boolean,
    actionInProgress: Boolean,
    testing: Boolean,
    copying: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onTest: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val config = row.config
    val status = row.status
    val name = config?.name ?: status?.name.orEmpty().ifBlank { row.id }
    val threshold = status?.threshold ?: config?.threshold ?: 0.0
    val operator = status?.operator ?: config?.operator.orEmpty().ifBlank { "-" }
    val address = config?.url.orEmpty().ifBlank { status?.url.orEmpty() }
    val method = status?.method.orEmpty().ifBlank { config?.method.orEmpty() }
    val valueTone = monitorValueTone(status?.lastValue, threshold, status?.breached == true)
    val valueColor = when (valueTone) {
        MonitorValueTone.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
        MonitorValueTone.ALERT -> MaterialTheme.colorScheme.error
        MonitorValueTone.WARNING -> Color(0xFFD97706)
        MonitorValueTone.HEALTHY -> Color(0xFF1B8A4B)
    }
    val cardModifier = Modifier
        .fillMaxWidth()
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer { translationY = dragOffset }

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = if (status?.breached == true) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 1.dp
        )
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showDragHandle) {
                    Spacer(Modifier.width(4.dp))
                    val dragModifier = if (canDrag) {
                        Modifier.pointerInput(row.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                    Box(
                        modifier = Modifier.size(34.dp).then(dragModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "长按拖动排序",
                            tint = if (canDrag) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactMonitorField(
                    label = "当前值",
                    value = status?.lastValue?.let(::formatNumber) ?: "尚无数据",
                    color = valueColor,
                    emphasize = true,
                    modifier = Modifier.weight(1f)
                )
                CompactMonitorField(
                    label = "报警条件",
                    value = "$operator ${formatNumber(threshold)}",
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "地址：" + (address.takeIf(String::isNotBlank)?.let {
                    if (canManage) it else displayMaskedMonitorUrl(it)
                } ?: "未配置"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactMonitorField(
                    label = "最近检测",
                    value = formatCompactTimeOrNever(status?.lastCheckAt ?: 0L),
                    modifier = Modifier.weight(1f)
                )
                CompactMonitorField(
                    label = "HTTP",
                    value = listOfNotNull(
                        method.takeIf(String::isNotBlank),
                        status?.lastHttpCode?.toString()
                    ).joinToString(" · ").ifBlank { "尚无记录" },
                    modifier = Modifier.weight(1f)
                )
            }
            if (canManage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CompactMonitorActionButton(
                            onClick = onTest,
                            enabled = !actionInProgress,
                            icon = Icons.Filled.PlayArrow,
                            contentDescription = if (testing) "测试中" else "测试监控"
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CompactMonitorActionButton(
                            onClick = onCopy,
                            enabled = !actionInProgress,
                            icon = Icons.Filled.AddCircle,
                            contentDescription = if (copying) "复制中" else "复制监控"
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CompactMonitorActionButton(
                            onClick = onEdit,
                            enabled = !actionInProgress,
                            icon = Icons.Filled.Edit,
                            contentDescription = "编辑监控"
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CompactMonitorActionButton(
                            onClick = onDelete,
                            enabled = !actionInProgress,
                            icon = Icons.Filled.Delete,
                            contentDescription = "删除监控",
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMonitorActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    contentDescription: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val colorAlpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = colorAlpha),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = contentColor.copy(alpha = colorAlpha)
        )
    }
}

@Composable
private fun EmptyMonitorState(
    config: RelayConfig,
    monitorConfigStatus: RemoteMonitorConfigStatus
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            when {
                !config.enabled -> "启用通信后可查看服务端监控状态"
                !config.isCompleteForUi() -> "请先完成服务端连接配置"
                monitorConfigStatus.loaded -> "服务端当前没有监控端点"
                else -> "尚未从服务端加载监控状态"
            },
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusLabel(text: String, color: Color) {
    Box(
        Modifier.background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun CompactMonitorField(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    emphasize: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Text(
            value,
            color = color,
            style = if (emphasize) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodySmall
            },
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RelayConfigurationDialog(
    current: RelayConfig,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSaveAndTest: (RelayConfig) -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(current.serverUrl) }
    var sharedToken by remember(current.sharedToken) { mutableStateOf(current.sharedToken) }
    var adminToken by remember(current.adminToken) { mutableStateOf(current.adminToken) }
    var enabled by rememberSaveable { mutableStateOf(current.enabled) }
    var validationError by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务端连接配置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务端地址") },
                    placeholder = { Text("https://alarm.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sharedToken,
                    onValueChange = { sharedToken = it },
                    label = { Text("共享密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = adminToken,
                    onValueChange = { adminToken = it },
                    label = { Text("管理员密钥（可选）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = current.deviceId,
                    onValueChange = { },
                    label = { Text("客户端 ID") },
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用通信", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (validationError.isNotBlank()) {
                    Text(validationError, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val candidate = current.copy(
                        serverUrl = serverUrl.trim().trimEnd('/'),
                        sharedToken = sharedToken.trim(),
                        adminToken = adminToken.trim(),
                        enabled = enabled
                    )
                    validationError = when {
                        !candidate.serverUrl.startsWith("https://") &&
                            !candidate.serverUrl.startsWith("http://") ->
                            "服务端地址必须以 http:// 或 https:// 开头"
                        candidate.sharedToken.isBlank() -> "请填写共享密钥"
                        else -> ""
                    }
                    if (validationError.isEmpty()) onSaveAndTest(candidate)
                }
            ) { Text("保存并检测") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SendAlarmDialog(
    sending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var message by rememberSaveable { mutableStateOf("请立即联系我，服务可能异常") }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("发送手动报警？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("其他客户端会立即响铃。")
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= 500) message = it },
                    label = { Text("报警内容") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(message.trim()) },
                enabled = message.isNotBlank() && !sending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(if (sending) "发送中" else "确认报警") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("取消") }
        }
    )
}

@Composable
private fun DeleteRemoteMonitorDialog(
    monitor: RemoteMonitorConfig,
    deleting: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除监控配置？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("将从服务端永久删除“${monitor.name}”，此操作无法撤销。")
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !deleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(if (deleting) "删除中" else "确认删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        }
    )
}

@Composable
private fun PermissionChecklist(revision: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val runtimeNotificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val alertChannelEnabled = notificationManager
        .getNotificationChannel(NotificationChannels.ALERT_CHANNEL_ID)
        ?.importance != NotificationManager.IMPORTANCE_NONE
    val notificationGranted = runtimeNotificationGranted &&
        notificationManager.areNotificationsEnabled() && alertChannelEnabled
    val dndGranted = notificationManager.isNotificationPolicyAccessGranted
    val fullScreenGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        notificationManager.canUseFullScreenIntent()
    val exactAlarmGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        alarmManager.canScheduleExactAlarms()
    val batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    val grantedCount = listOf(
        notificationGranted,
        dndGranted,
        fullScreenGranted,
        exactAlarmGranted,
        batteryExempt
    ).count { it }
    var expanded by rememberSaveable { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val refreshKey = revision
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "紧急报警权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$grantedCount / 5 已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(if (expanded) "收起" else "查看", style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                PermissionRow("通知权限", notificationGranted) { openAppNotificationSettings(context) }
                PermissionRow("勿扰模式控制", dndGranted) {
                    openSettings(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
                PermissionRow("全屏紧急提醒", fullScreenGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        openSettings(
                            context,
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
                PermissionRow("忽略电池优化", batteryExempt) {
                    openSettings(
                        context,
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
                PermissionRow("后台恢复精确闹钟", exactAlarmGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openSettings(
                            context,
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusLabel(
            if (granted) "已授权" else "未授权",
            if (granted) Color(0xFF1B7F4B) else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) TextButton(onClick = onOpen) { Text("去设置") }
    }
}

private data class RelayStatusPresentation(
    val title: String,
    val detail: String,
    val kind: RelayStatusKind
) {
    @Composable
    fun indicatorColor(): Color = when (kind) {
        RelayStatusKind.ONLINE -> Color(0xFF1B7F4B)
        RelayStatusKind.CHECKING -> MaterialTheme.colorScheme.primary
        RelayStatusKind.OFFLINE -> MaterialTheme.colorScheme.error
        RelayStatusKind.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    @Composable
    fun containerColor(): Color = when (kind) {
        RelayStatusKind.ONLINE -> Color(0xFFE6F4EA)
        RelayStatusKind.CHECKING -> MaterialTheme.colorScheme.primaryContainer
        RelayStatusKind.OFFLINE -> MaterialTheme.colorScheme.errorContainer
        RelayStatusKind.DISABLED -> MaterialTheme.colorScheme.surfaceVariant
    }
}

private enum class RelayStatusKind { ONLINE, CHECKING, OFFLINE, DISABLED }
private class DragAutoScrollState {
    var job: Job? = null
    var speed: Float = 0f
}

private data class DragTarget(
    val item: androidx.compose.foundation.lazy.LazyListItemInfo,
    val id: String,
    val orderIndex: Int,
    val center: Float
)

private fun relayStatusPresentation(
    config: RelayConfig,
    status: RelayStatus,
    testing: Boolean
): RelayStatusPresentation {
    if (!config.enabled) {
        return RelayStatusPresentation(
            "通信未启用",
            "保存完整配置并启用后开始值守",
            RelayStatusKind.DISABLED
        )
    }
    if (!config.isCompleteForUi()) {
        return RelayStatusPresentation(
            "配置待完成",
            "服务端地址和共享密钥为必填项",
            RelayStatusKind.OFFLINE
        )
    }
    if (testing || status.state == RelayConnectionState.CHECKING) {
        return RelayStatusPresentation(
            "正在检测服务端",
            "正在验证服务端地址和凭据",
            RelayStatusKind.CHECKING
        )
    }
    return when (status.state) {
        RelayConnectionState.ONLINE -> RelayStatusPresentation(
            "服务端在线",
            "在线客户端 ${status.onlineClients} 个 | 最近检测 ${formatTimeOrNever(status.lastCheckedAt)}",
            RelayStatusKind.ONLINE
        )
        RelayConnectionState.OFFLINE -> RelayStatusPresentation(
            "服务端离线",
            status.lastError.ifBlank { "尚未成功连接服务端" },
            RelayStatusKind.OFFLINE
        )
        RelayConnectionState.CHECKING -> RelayStatusPresentation(
            "正在检测服务端",
            "正在验证服务端地址和凭据",
            RelayStatusKind.CHECKING
        )
        RelayConnectionState.DISABLED -> RelayStatusPresentation(
            "等待服务启动",
            "配置已启用，正在等待后台通信服务",
            RelayStatusKind.CHECKING
        )
    }
}

private fun RelayConfig.isCompleteForUi(): Boolean =
    (serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) &&
        sharedToken.isNotBlank() && RelayConfig.isValidIdentifier(deviceId)

private fun formatTimeOrNever(timestamp: Long): String =
    if (timestamp <= 0L) "尚无记录" else DateFormat.getDateTimeInstance().format(timestamp)

private fun formatCompactTimeOrNever(timestamp: Long): String =
    if (timestamp <= 0L) {
        "尚无记录"
    } else {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(timestamp)
    }

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else "%.6f".format(value).trimEnd('0').trimEnd('.')

private fun displayMaskedMonitorUrl(value: String): String = runCatching {
    val uri = java.net.URI(value)
    require(uri.scheme == "http" || uri.scheme == "https")
    val host = requireNotNull(uri.host)
    val displayHost = if (':' in host) "[$host]" else host
    val maskedQuery = uri.rawQuery
        ?.takeIf(String::isNotBlank)
        ?.split('&')
        ?.joinToString("&") { field ->
            val name = field.substringBefore('=').ifBlank { "key" }
            "$name=****"
        }
    buildString {
        append(uri.scheme).append("://").append(displayHost)
        if (uri.port >= 0) append(':').append(uri.port)
        append(uri.rawPath.orEmpty())
        if (maskedQuery != null) append('?').append(maskedQuery)
    }
}.getOrDefault("地址格式无效")

private fun Throwable.readableMessage(): String =
    message?.take(300)?.ifBlank { null } ?: javaClass.simpleName

private fun openAppNotificationSettings(context: Context) {
    openSettings(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    )
}

private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            Toast.makeText(context, "无法打开该系统设置页面", Toast.LENGTH_SHORT).show()
        }
}
