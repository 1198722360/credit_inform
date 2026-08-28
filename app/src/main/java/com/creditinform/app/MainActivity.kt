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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.creditinform.app.data.RemoteMonitorTestResult
import com.creditinform.app.monitoring.AlarmController
import com.creditinform.app.monitoring.NotificationChannels
import com.creditinform.app.monitoring.RelayService
import com.creditinform.app.monitoring.UrgentAlarm
import com.creditinform.app.network.RelayApiException
import com.creditinform.app.ui.CreditInformTheme
import com.creditinform.app.ui.RemoteMonitorEditorScreen
import kotlinx.coroutines.launch
import java.text.DateFormat

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
    var adminLoading by remember { mutableStateOf(false) }
    var adminError by remember { mutableStateOf("") }
    var adminActionId by remember { mutableStateOf<String?>(null) }
    var showMonitorEditor by remember { mutableStateOf(false) }
    var editingMonitor by remember { mutableStateOf<RemoteMonitorConfig?>(null) }
    var editorSaving by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RemoteMonitorConfig?>(null) }
    var deleteError by remember { mutableStateOf("") }
    var testFeedback by remember { mutableStateOf<MonitorTestFeedback?>(null) }
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

    LaunchedEffect(config.serverUrl, config.groupId, config.adminToken) {
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
            adminLoading = adminLoading,
            adminError = adminError,
            adminActionId = adminActionId,
            permissionRevision = permissionRevision,
            onOpenConfiguration = { showConfiguration = true },
            onTestConnection = {
                scope.launch {
                    testing = true
                    repository.testConnection()
                        .onSuccess { health ->
                            Toast.makeText(
                                context,
                                "连接成功，在线客户端 ${health.onlineClients} 个",
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
                    refreshing = true
                    repository.refreshMonitors().onFailure { error ->
                        Toast.makeText(
                            context,
                            "刷新失败：${error.readableMessage()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    refreshing = false
                }
            },
            onRefreshAdmin = {
                scope.launch {
                    adminLoading = true
                    adminError = ""
                    repository.listRemoteMonitorConfigs().onFailure { error ->
                        adminError = "加载监控配置失败：${error.readableMessage()}"
                    }
                    adminLoading = false
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
            onTestMonitor = { monitor ->
                scope.launch {
                    adminActionId = monitor.id
                    repository.testRemoteMonitor(monitor.id)
                        .onSuccess { result ->
                            testFeedback = MonitorTestFeedback(monitor.name, result = result)
                        }
                        .onFailure { error ->
                            testFeedback = MonitorTestFeedback(
                                monitorName = monitor.name,
                                error = error.readableMessage()
                            )
                        }
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

    testFeedback?.let { feedback ->
        RemoteMonitorTestDialog(feedback = feedback, onDismiss = { testFeedback = null })
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
    adminLoading: Boolean,
    adminError: String,
    adminActionId: String?,
    permissionRevision: Int,
    onOpenConfiguration: () -> Unit,
    onTestConnection: () -> Unit,
    onRequestSendAlarm: () -> Unit,
    onTestLocalAlarm: () -> Unit,
    onRefreshMonitors: () -> Unit,
    onRefreshAdmin: () -> Unit,
    onCreateMonitor: () -> Unit,
    onEditMonitor: (RemoteMonitorConfig) -> Unit,
    onDeleteMonitor: (RemoteMonitorConfig) -> Unit,
    onTestMonitor: (RemoteMonitorConfig) -> Unit
) {
    Scaffold { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "值守报警",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        config.deviceName.ifBlank { "本机尚未配置" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                    count = monitors.size,
                    refreshing = refreshing,
                    canRefresh = config.enabled && config.isCompleteForUi(),
                    onRefresh = onRefreshMonitors
                )
            }
            if (refreshing) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            if (monitorConfigStatus.lastError.isNotBlank()) {
                item {
                    Text(
                        "服务端监控配置异常：${monitorConfigStatus.lastError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (monitors.isEmpty() && !refreshing) {
                item { EmptyMonitorState(config, monitorConfigStatus) }
            } else {
                items(monitors, key = { "status:${it.id}" }) { RemoteMonitorCard(it) }
            }
            if (config.adminToken.isNotBlank()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                }
                item {
                    RemoteMonitorAdminHeader(
                        count = remoteMonitorConfigs.size,
                        loading = adminLoading,
                        canManage = config.hasAdminAccess,
                        onRefresh = onRefreshAdmin,
                        onCreate = onCreateMonitor
                    )
                }
                if (adminLoading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
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
                if (remoteMonitorConfigs.isEmpty() && !adminLoading) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "服务端尚无可管理的监控配置",
                                modifier = Modifier.padding(20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(remoteMonitorConfigs, key = { "config:${it.id}" }) { monitor ->
                        RemoteMonitorAdminCard(
                            monitor = monitor,
                            actionInProgress = adminLoading || adminActionId != null,
                            testing = adminActionId == monitor.id,
                            onEdit = { onEditMonitor(monitor) },
                            onDelete = { onDeleteMonitor(monitor) },
                            onTest = { onTestMonitor(monitor) }
                        )
                    }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (config.serverUrl.isNotBlank()) {
                Text(
                    displayUrlWithoutQuery(config.serverUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "通信组：${config.groupId.ifBlank { "未配置" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenConfiguration,
                    modifier = Modifier.weight(1f)
                ) { Text("连接配置") }
                Button(
                    onClick = onTestConnection,
                    enabled = config.isCompleteForUi() && !testing,
                    modifier = Modifier.weight(1f)
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "手动报警",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onRequestSendAlarm,
                enabled = config.enabled && config.isCompleteForUi() && !sending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (sending) "正在发送" else "向其他客户端报警") }
            OutlinedButton(
                onClick = onTestLocalAlarm,
                modifier = Modifier.fillMaxWidth()
            ) { Text("测试本机报警") }
        }
    }
}

@Composable
private fun RemoteMonitorHeader(
    count: Int,
    refreshing: Boolean,
    canRefresh: Boolean,
    onRefresh: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "服务端监控 ($count)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRefresh, enabled = canRefresh && !refreshing) {
            Text(if (refreshing) "刷新中" else "刷新")
        }
    }
}

@Composable
private fun RemoteMonitorAdminHeader(
    count: Int,
    loading: Boolean,
    canManage: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "监控配置管理 ($count)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh, enabled = canManage && !loading) {
                Text(if (loading) "加载中" else "重新加载")
            }
        }
        Button(
            onClick = onCreate,
            enabled = canManage && !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("新增服务端监控") }
    }
}

@Composable
private fun RemoteMonitorAdminCard(
    monitor: RemoteMonitorConfig,
    actionInProgress: Boolean,
    testing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monitor.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                StatusLabel(
                    if (monitor.enabled) "已启用" else "已停用",
                    if (monitor.enabled) Color(0xFF1B7F4B)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LabeledText("请求", "${monitor.method} ${displayUrlWithoutQuery(monitor.url)}")
            LabeledText("取值字段", monitor.jsonPath)
            LabeledText("报警条件", "${monitor.operator} ${formatNumber(monitor.threshold)}")
            LabeledText("配置版本", "${monitor.revision} | ${formatTimeOrNever(monitor.updatedAt)}")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onTest, enabled = !actionInProgress) {
                    Text(if (testing) "测试中" else "立即测试")
                }
                OutlinedButton(onClick = onEdit, enabled = !actionInProgress) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = !actionInProgress) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
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
private fun RemoteMonitorCard(monitor: RemoteMonitorStatus) {
    val state = when {
        !monitor.enabled -> MonitorPresentation("已停用", MaterialTheme.colorScheme.onSurfaceVariant)
        monitor.breached -> MonitorPresentation("已报警", MaterialTheme.colorScheme.error)
        monitor.lastError.isNotBlank() -> MonitorPresentation("检测异常", Color(0xFFD97706))
        monitor.lastCheckAt == 0L -> MonitorPresentation("等待检测", MaterialTheme.colorScheme.onSurfaceVariant)
        else -> MonitorPresentation("正常", Color(0xFF1B7F4B))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (monitor.breached) {
                MaterialTheme.colorScheme.errorContainer
            } else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monitor.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                StatusLabel(state.label, state.color)
            }
            HorizontalDivider()
            LabeledText(
                "当前值",
                monitor.lastValue?.let(::formatNumber) ?: "尚无数据",
                if (monitor.breached) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
            LabeledText("报警条件", "${monitor.operator} ${formatNumber(monitor.threshold)}")
            LabeledText("最近检测", formatTimeOrNever(monitor.lastCheckAt))
            monitor.lastHttpCode?.let { LabeledText("HTTP", it.toString()) }
            if (monitor.lastError.isNotBlank()) {
                Text(
                    monitor.lastError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
private fun LabeledText(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label：",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
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
    var groupId by rememberSaveable { mutableStateOf(current.groupId) }
    var sharedToken by remember(current.sharedToken) { mutableStateOf(current.sharedToken) }
    var adminToken by remember(current.adminToken) { mutableStateOf(current.adminToken) }
    var deviceName by rememberSaveable { mutableStateOf(current.deviceName) }
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
                    value = groupId,
                    onValueChange = { groupId = it },
                    label = { Text("通信组") },
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
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("客户端名称") },
                    placeholder = { Text("例如：客服端、我的手机") },
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
                        groupId = groupId.trim(),
                        sharedToken = sharedToken.trim(),
                        adminToken = adminToken.trim(),
                        deviceName = deviceName.trim(),
                        enabled = enabled
                    )
                    validationError = when {
                        !candidate.serverUrl.startsWith("https://") &&
                            !candidate.serverUrl.startsWith("http://") ->
                            "服务端地址必须以 http:// 或 https:// 开头"
                        !RelayConfig.isValidGroupId(candidate.groupId) ->
                            "通信组必须为 1 到 64 位字母、数字、点、下划线或短横线"
                        candidate.sharedToken.isBlank() -> "请填写共享密钥"
                        candidate.deviceName.isBlank() -> "请填写客户端名称"
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
                Text("同一通信组内的其他客户端会立即响铃。")
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

private data class MonitorTestFeedback(
    val monitorName: String,
    val result: RemoteMonitorTestResult? = null,
    val error: String = ""
)

@Composable
private fun RemoteMonitorTestDialog(
    feedback: MonitorTestFeedback,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${feedback.monitorName} 测试结果") },
        text = {
            val result = feedback.result
            if (result == null) {
                Text(
                    "测试失败：${feedback.error.ifBlank { "服务端未返回结果" }}",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledText("HTTP", result.httpCode.toString())
                    LabeledText("接口原始值", result.rawValue?.let(::formatNumber) ?: "未取得")
                    LabeledText("换算后数值", result.value?.let(::formatNumber) ?: "未取得")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "报警判断：",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StatusLabel(
                            if (result.breached) "已越界" else "正常",
                            if (result.breached) MaterialTheme.colorScheme.error
                            else Color(0xFF1B7F4B)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
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
private data class MonitorPresentation(val label: String, val color: Color)

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
            "服务器、通信组、密钥和客户端名称均为必填项",
            RelayStatusKind.OFFLINE
        )
    }
    if (testing || status.state == RelayConnectionState.CHECKING) {
        return RelayStatusPresentation(
            "正在检测服务端",
            "正在验证地址、通信组和凭据",
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
            "正在验证地址、通信组和凭据",
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
        RelayConfig.isValidGroupId(groupId) && sharedToken.isNotBlank() &&
        deviceId.isNotBlank() && deviceName.isNotBlank()

private fun formatTimeOrNever(timestamp: Long): String =
    if (timestamp <= 0L) "尚无记录" else DateFormat.getDateTimeInstance().format(timestamp)

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else "%.6f".format(value).trimEnd('0').trimEnd('.')

private fun displayUrlWithoutQuery(value: String): String = runCatching {
    val uri = java.net.URI(value)
    java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
}.getOrDefault(value.substringBefore('?').substringBefore('#'))

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
