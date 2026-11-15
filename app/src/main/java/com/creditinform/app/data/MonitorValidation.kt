package com.creditinform.app.data

import java.net.URI
import org.json.JSONObject

internal fun validateMonitorRequest(r: RemoteMonitorUpsertRequest) {
        require(r.name.isNotBlank() && r.name.length <= 128) { "名称必须为 1 到 128 个字符" }
        val uri = URI(r.url)
        require(r.url.length <= 2048 && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "接口地址或类型无效" }
        require(r.method in setOf("GET", "POST") && r.provider in setOf("http", "gateway")) { "接口地址或类型无效" }
        require(r.jsonPath.isNotBlank() && r.jsonPath.length <= 512) { "请填写 JSON 数值路径" }
        require(r.operator in setOf("<", "<=", ">", ">=", "=", "==")) { "比较符无效" }
        require(r.threshold.isFinite() && r.multiplier.isFinite() && r.offset.isFinite()) { "阈值和换算参数无效" }
        require(r.pollIntervalSeconds in 5..86400 && r.timeoutSeconds in 1..60 && r.errorThreshold in 1..100) { "监控列表已变更或检测参数无效" }
        require(r.repeatAlertSeconds == 0L || r.repeatAlertSeconds in 15..604800) { "无法保存本机设置或参数无效" }
        r.headersJson?.let { JSONObject(it) }
        require(r.body == null || r.body.toByteArray().size <= 32768) { "请求体超过 32 KiB" }
        if (r.provider == "gateway") {
            require(Regex("gwak_[0-9a-f]{24}").matches(r.gatewayAccessKey) && Regex("gwsk_[A-Za-z0-9_-]{43}").matches(r.gatewaySecretKey)) { "GateWay AK 或 SK 格式无效" }
            require(uri.scheme == "https" && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("balance", "nodes", "logs", "bans").map { "/open/v1/$it/query" }) { "GateWay 地址无效" }
            require(r.pollIntervalSeconds >= 30) { "GateWay 检测间隔不能少于 30 秒" }
            r.body?.let { JSONObject(it) }
        }
    }
