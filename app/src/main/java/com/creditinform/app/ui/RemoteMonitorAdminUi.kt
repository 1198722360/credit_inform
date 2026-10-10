package com.creditinform.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorUpsertRequest
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

private data class SecretFieldDraft(
    val id: Long,
    val originalName: String?,
    val name: String,
    val value: String,
    val keepExisting: Boolean
)

private enum class RequestBodyDraftMode { KEEP, REPLACE, CLEAR }

@Composable
fun RemoteMonitorEditorScreen(
    existing: RemoteMonitorConfig?,
    saving: Boolean,
    externalError: String = "",
    onCancel: () -> Unit,
    onSave: (RemoteMonitorUpsertRequest) -> Unit
) {
    BackHandler { if (!saving) onCancel() }
    val initialUrl = remember(existing?.id) { splitMonitorUrl(existing?.url.orEmpty()) }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var urlBase by rememberSaveable(existing?.id) { mutableStateOf(initialUrl.baseUrl) }
    var queryFields by remember(existing?.id) { mutableStateOf(initialUrl.queryFields) }
    var method by rememberSaveable(existing?.id) { mutableStateOf(existing?.method ?: "GET") }
    var headerFields by remember(existing?.id) {
        mutableStateOf(parseSecretObject(existing?.headersJson))
    }
    val bodyAlreadyConfigured = existing?.bodyConfigured == true
    val initialBody = existing?.body
        ?.takeUnless { it == RemoteMonitorConfig.KEEP_PLACEHOLDER }
        .orEmpty()
    var bodyMode by remember(existing?.id) {
        mutableStateOf(
            if (initialBody.isNotEmpty()) RequestBodyDraftMode.REPLACE
            else RequestBodyDraftMode.KEEP
        )
    }
    var body by remember(existing?.id) { mutableStateOf(initialBody) }
    var jsonPath by rememberSaveable(existing?.id) { mutableStateOf(existing?.jsonPath.orEmpty()) }
    var operator by rememberSaveable(existing?.id) { mutableStateOf(existing?.operator ?: "<") }
    var thresholdText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.threshold?.toString().orEmpty())
    }
    var multiplierText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.multiplier ?: 1.0).toString())
    }
    var offsetText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.offset ?: 0.0).toString())
    }
    var pollText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.pollIntervalSeconds ?: 60).toString())
    }
    var timeoutText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.timeoutSeconds ?: 15).toString())
    }
    var errorThresholdText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.errorThreshold ?: 3).toString())
    }
    var repeatAlertText by rememberSaveable(existing?.id) {
        mutableStateOf((existing?.repeatAlertSeconds ?: 300).toString())
    }
    var enabled by rememberSaveable(existing?.id) { mutableStateOf(existing?.enabled ?: true) }
    var sampleRaw by rememberSaveable(existing?.id) { mutableStateOf("300") }
    var sampleActual by rememberSaveable(existing?.id) { mutableStateOf("5") }
    var validationError by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (existing == null) "新增服务端监控" else "编辑服务端监控",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel, enabled = !saving) { Text("取消") }
            }
        }

        item { EditorSectionTitle("接口") }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                placeholder = { Text("例如：主账号余额") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = urlBase,
                onValueChange = { value ->
                    if ('?' in value) {
                        val split = splitMonitorUrl(value)
                        urlBase = split.baseUrl
                        queryFields = split.queryFields
                    } else {
                        urlBase = value.substringBefore('#')
                    }
                },
                label = { Text("接口地址（不含查询参数）") },
                placeholder = { Text("https://example.com/api/balance") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            SecretFieldList(
                title = "查询参数",
                fields = queryFields,
                addLabel = "添加查询参数",
                nameLabel = "参数名",
                onChange = { queryFields = it }
            )
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("GET", "POST").forEach { option ->
                    FilterChip(
                        selected = method == option,
                        onClick = { method = option },
                        label = { Text(option) }
                    )
                }
            }
        }

        item { EditorSectionTitle("请求凭据") }
        item {
            SecretFieldList(
                title = "请求头",
                fields = headerFields,
                addLabel = "添加请求头",
                nameLabel = "名称",
                onChange = { headerFields = it }
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请求体", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = if (bodyAlreadyConfigured) {
                        listOf(
                            RequestBodyDraftMode.KEEP to "保留",
                            RequestBodyDraftMode.REPLACE to "替换",
                            RequestBodyDraftMode.CLEAR to "清除"
                        )
                    } else {
                        listOf(
                            RequestBodyDraftMode.KEEP to "不配置",
                            RequestBodyDraftMode.REPLACE to "配置"
                        )
                    }
                    options.forEach { (option, label) ->
                        FilterChip(
                            selected = bodyMode == option,
                            onClick = { bodyMode = option },
                            label = { Text(label) }
                        )
                    }
                }
                if (bodyMode == RequestBodyDraftMode.CLEAR) {
                    Text(
                        "保存后将清除服务端请求体",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (bodyMode == RequestBodyDraftMode.REPLACE) {
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("请求体") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item { EditorSectionTitle("字段与报警规则") }
        item {
            OutlinedTextField(
                value = jsonPath,
                onValueChange = { jsonPath = it },
                label = { Text("JSON 字段路径") },
                placeholder = { Text("data.balance 或 accounts[0].remaining") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("<", "<=", ">", ">=", "=").forEach { option ->
                    FilterChip(
                        selected = operator == option,
                        onClick = { operator = option },
                        label = { Text(option) }
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { thresholdText = it },
                label = { Text("换算后的报警值") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { EditorSectionTitle("数值换算") }
        item {
            Text(
                "实际值 = 接口原始值 x 倍率 + 偏移量",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = multiplierText,
                    onValueChange = { multiplierText = it },
                    label = { Text("倍率") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { offsetText = it },
                    label = { Text("偏移量") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("按样本计算倍率", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sampleRaw,
                        onValueChange = { sampleRaw = it },
                        label = { Text("原始值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sampleActual,
                        onValueChange = { sampleActual = it },
                        label = { Text("对应实际值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        val raw = sampleRaw.toDoubleOrNull()
                        val actual = sampleActual.toDoubleOrNull()
                        if (raw != null && raw != 0.0 && actual != null) {
                            multiplierText = (actual / raw).toString()
                            offsetText = "0.0"
                        } else {
                            validationError = "换算样本必须是有效数值，且原始值不能为 0"
                        }
                    }
                ) { Text("计算倍率") }
            }
        }

        item { EditorSectionTitle("检测与重复提醒") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericEditor(
                    value = pollText,
                    onValueChange = { pollText = it },
                    label = "检测间隔（秒）",
                    modifier = Modifier.weight(1f)
                )
                NumericEditor(
                    value = timeoutText,
                    onValueChange = { timeoutText = it },
                    label = "超时（秒）",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumericEditor(
                    value = errorThresholdText,
                    onValueChange = { errorThresholdText = it },
                    label = "连续错误次数",
                    modifier = Modifier.weight(1f)
                )
                NumericEditor(
                    value = repeatAlertText,
                    onValueChange = { repeatAlertText = it },
                    label = "重复报警（秒）",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("启用此监控", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
        if (validationError.isNotBlank()) {
            item { Text(validationError, color = MaterialTheme.colorScheme.error) }
        }
        if (externalError.isNotBlank()) {
            item { Text(externalError, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Button(
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val threshold = thresholdText.toDoubleOrNull()
                    val multiplier = multiplierText.toDoubleOrNull()
                    val offset = offsetText.toDoubleOrNull()
                    val poll = pollText.toLongOrNull()
                    val timeout = timeoutText.toIntOrNull()
                    val errorThreshold = errorThresholdText.toIntOrNull()
                    val repeat = repeatAlertText.toLongOrNull()
                    validationError = validateEditor(
                        name = name,
                        urlBase = urlBase,
                        queryFields = queryFields,
                        headerFields = headerFields,
                        jsonPath = jsonPath,
                        threshold = threshold,
                        multiplier = multiplier,
                        offset = offset,
                        poll = poll,
                        timeout = timeout,
                        errorThreshold = errorThreshold,
                        repeat = repeat,
                        replaceBody = bodyMode == RequestBodyDraftMode.REPLACE,
                        body = body
                    )
                    if (validationError.isEmpty()) {
                        val requestUrl = buildMonitorUrl(
                            baseUrl = urlBase,
                            queryFields = queryFields
                        )
                        onSave(
                            RemoteMonitorUpsertRequest(
                                name = name.trim(),
                                url = requestUrl,
                                method = method,
                                headersJson = secretFieldsToJson(headerFields),
                                body = body.takeIf {
                                    bodyMode == RequestBodyDraftMode.REPLACE
                                },
                                bodySpecified = bodyMode != RequestBodyDraftMode.KEEP,
                                jsonPath = jsonPath.trim(),
                                operator = operator,
                                threshold = threshold!!,
                                multiplier = multiplier!!,
                                offset = offset!!,
                                pollIntervalSeconds = poll!!,
                                timeoutSeconds = timeout!!,
                                errorThreshold = errorThreshold!!,
                                repeatAlertSeconds = repeat!!,
                                enabled = enabled,
                                revision = existing?.revision
                            )
                        )
                    }
                }
            ) { Text(if (saving) "保存中" else "保存到服务端") }
        }
        item { Spacer(Modifier.width(1.dp)) }
    }
}

@Composable
private fun SecretFieldList(
    title: String,
    fields: List<SecretFieldDraft>,
    addLabel: String,
    nameLabel: String,
    onChange: (List<SecretFieldDraft>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    onChange(
                        fields + SecretFieldDraft(
                            id = System.nanoTime(),
                            originalName = null,
                            name = "",
                            value = "",
                            keepExisting = false
                        )
                    )
                }
            ) { Text(addLabel) }
        }
        if (fields.isEmpty()) {
            Text(
                "未配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        fields.forEach { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = field.name,
                        onValueChange = { newName ->
                            onChange(fields.replace(field.id) { it.copy(name = newName) })
                        },
                        label = { Text(nameLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onChange(fields.filterNot { it.id == field.id }) }) {
                        Text("删除")
                    }
                }
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { newValue ->
                        onChange(fields.replace(field.id) { it.copy(value = newValue) })
                    },
                    label = { Text("值") },
                    placeholder = {
                        Text(
                            if (field.keepExisting) "已配置，留空则保留" else "请输入值"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NumericEditor(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun EditorSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun validateEditor(
    name: String,
    urlBase: String,
    queryFields: List<SecretFieldDraft>,
    headerFields: List<SecretFieldDraft>,
    jsonPath: String,
    threshold: Double?,
    multiplier: Double?,
    offset: Double?,
    poll: Long?,
    timeout: Int?,
    errorThreshold: Int?,
    repeat: Long?,
    replaceBody: Boolean,
    body: String
): String {
    val uri = runCatching { URI(urlBase.trim()) }.getOrNull()
    val invalidSecret = (queryFields + headerFields).firstOrNull {
        it.name.isBlank() ||
            (it.value.isBlank() && (!it.keepExisting || it.name != it.originalName))
    }
    return when {
        name.isBlank() -> "请填写监控名称"
        uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ->
            "接口地址必须是有效的 http:// 或 https:// 地址"
        invalidSecret != null -> "查询参数和请求头的名称、值必须完整"
        queryFields.map { it.name }.let { it.size != it.distinct().size } -> "查询参数名称不能重复"
        headerFields.map { it.name.lowercase() }.let { it.size != it.distinct().size } ->
            "请求头名称不能重复"
        replaceBody && body.isBlank() -> "请输入新的请求体"
        jsonPath.isBlank() -> "请填写 JSON 字段路径"
        threshold == null || !threshold.isFinite() -> "报警值不是有效数字"
        multiplier == null || !multiplier.isFinite() -> "倍率不是有效数字"
        offset == null || !offset.isFinite() -> "偏移量不是有效数字"
        poll == null || poll < 5 -> "检测间隔不能少于 5 秒"
        timeout == null || timeout !in 1..60 -> "超时时间必须为 1 到 60 秒"
        errorThreshold == null || errorThreshold !in 1..100 ->
            "连续错误次数必须为 1 到 100"
        repeat == null || (repeat != 0L && repeat < 15L) ->
            "重复报警间隔必须为 0 或至少 15 秒"
        else -> ""
    }
}

private data class InitialUrl(
    val baseUrl: String,
    val queryFields: List<SecretFieldDraft>
)

private fun splitMonitorUrl(value: String): InitialUrl {
    if (value.isBlank()) return InitialUrl("", emptyList())
    return runCatching {
        val uri = URI(value.trim())
        val base = buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority)
            append(uri.rawPath.orEmpty())
        }
        val fields = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .mapIndexed { index, part ->
                val name = urlDecode(part.substringBefore('='))
                val rawValue = urlDecode(part.substringAfter('=', ""))
                SecretFieldDraft(
                    id = System.nanoTime() + index,
                    originalName = name,
                    name = name,
                    value = rawValue.takeUnless { it == RemoteMonitorConfig.KEEP_PLACEHOLDER }.orEmpty(),
                    keepExisting = rawValue == RemoteMonitorConfig.KEEP_PLACEHOLDER
                )
            }
        InitialUrl(base, fields)
    }.getOrElse {
        InitialUrl(value.substringBefore('?').substringBefore('#'), emptyList())
    }
}

private fun parseSecretObject(value: String?): List<SecretFieldDraft> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        val json = JSONObject(value)
        json.keys().asSequence().mapIndexed { index, name ->
            val rawValue = json.get(name).toString()
            SecretFieldDraft(
                id = System.nanoTime() + index,
                originalName = name,
                name = name,
                value = rawValue.takeUnless { it == RemoteMonitorConfig.KEEP_PLACEHOLDER }.orEmpty(),
                keepExisting = rawValue == RemoteMonitorConfig.KEEP_PLACEHOLDER
            )
        }.toList()
    }.getOrDefault(emptyList())
}

private fun secretFieldsToJson(fields: List<SecretFieldDraft>): String = JSONObject().apply {
    fields.forEach { field ->
        put(
            field.name.trim(),
            field.value.takeIf { it.isNotBlank() }
                ?: RemoteMonitorConfig.KEEP_PLACEHOLDER
        )
    }
}.toString()

private fun buildMonitorUrl(
    baseUrl: String,
    queryFields: List<SecretFieldDraft>
): String {
    val base = baseUrl.trim().trimEnd('?')
    val query = queryFields.joinToString("&") { field ->
        val value = field.value.takeIf { it.isNotBlank() }
            ?: RemoteMonitorConfig.KEEP_PLACEHOLDER
        "${urlEncode(field.name.trim())}=${urlEncode(value)}"
    }
    return if (query.isBlank()) base else "$base?$query"
}

private fun List<SecretFieldDraft>.replace(
    id: Long,
    update: (SecretFieldDraft) -> SecretFieldDraft
): List<SecretFieldDraft> = map { if (it.id == id) update(it) else it }

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun urlDecode(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8.name())
