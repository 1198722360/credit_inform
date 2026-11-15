package com.creditinform.app.network

import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorTestResult
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Only connects to the business endpoint explicitly configured on this phone. */
class LocalMonitorClient {
    fun check(config: RemoteMonitorConfig): RemoteMonitorTestResult {
        val uri = URI(config.url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "接口地址必须是有效的 HTTP 或 HTTPS 地址"
        }
        val gateway = config.provider == "gateway"
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val keys = if (gateway) GatewayCrypto.deriveKeys(config.gatewayAccessKey, config.gatewaySecretKey) else null
        val headers = if (gateway) JSONObject().apply {
            put("X-Gw-Access-Key", config.gatewayAccessKey)
            put("X-Gw-Timestamp", timestamp)
            put("X-Gw-Nonce", nonce)
        } else JSONObject(config.headersJson ?: "{}")
        val body = if (gateway) {
            require(uri.scheme == "https" && uri.rawQuery == null && uri.rawFragment == null &&
                uri.path in listOf("balance", "nodes", "logs", "bans").map { "/open/v1/$it/query" }) { "GateWay 接口地址无效" }
            val plaintext = JSONObject(config.body ?: "{}").toString()
            require(plaintext.toByteArray().size <= 32768) { "请求体超过 32 KiB" }
            JSONObject().put("payload", GatewayCrypto.seal(keys!!.first, plaintext,
                "GW1-REQ\nPOST\n${uri.path}\n${config.gatewayAccessKey}\n$timestamp\n$nonce")).toString()
        } else config.body
        require(body == null || body.toByteArray().size <= 32768) { "请求体超过 32 KiB" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        var code: Int? = null
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = config.timeoutSeconds * 1000
            connection.readTimeout = config.timeoutSeconds * 1000
            connection.requestMethod = if (gateway) "POST" else config.method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.keys().forEach { connection.setRequestProperty(it, headers.getString(it)) }
            if (body != null && connection.requestMethod == "POST") {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            code = connection.responseCode
            if (code !in 200..299) throw RelayApiException(code, "业务接口返回 HTTP $code")
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1048576) throw RelayApiException(code, "接口响应超过 1 MiB")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            var document: Any = JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()
            if (gateway) {
                val envelope = document as JSONObject
                require(envelope.has("payload")) { "GateWay 响应未加密" }
                document = JSONObject(GatewayCrypto.open(keys!!.second, envelope.getString("payload"),
                    "GW1-RESP\n${config.gatewayAccessKey}\n$nonce"))
                require(document.getString("code") == "OK" && document.optJSONObject("data") != null) { "GateWay 业务请求失败" }
            }
            val raw = readNumber(document, config.jsonPath)
            val value = raw * config.multiplier + config.offset
            require(value.isFinite()) { "换算结果不是有限数值" }
            return RemoteMonitorTestResult(System.currentTimeMillis(), code, raw, value,
                breached(value, config.operator, config.threshold))
        } catch (error: RelayApiException) {
            throw error
        } catch (error: java.net.SocketTimeoutException) {
            throw RelayApiException(code ?: 0, "业务接口请求超时")
        } catch (error: java.io.IOException) {
            throw RelayApiException(code ?: 0, "无法连接业务接口，请检查手机网络和接口地址")
        } catch (error: Exception) {
            // Do not expose response bodies, URLs, headers or keys in notifications.
            throw RelayApiException(code ?: 0, "接口响应无法验证或数值字段无效，请检查接口配置")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun breached(value: Double, operator: String, threshold: Double): Boolean = when (operator) {
            "<" -> value < threshold
            "<=" -> value <= threshold
            ">" -> value > threshold
            ">=" -> value >= threshold
            "=", "==" -> value == threshold
            else -> error("不支持的比较符")
        }

        fun readNumber(document: Any, path: String): Double {
            val normalized = path.removePrefix("$").removePrefix(".")
            val tokens = Regex("([^.\\[\\]]+)|\\[(\\d+)\\]").findAll(normalized).toList()
            require(tokens.isNotEmpty()) { "JSON 路径为空" }
            var end = 0
            var current: Any = document
            tokens.forEach { token ->
                val gap = normalized.substring(end, token.range.first)
                require(gap.isEmpty() || (end > 0 && gap == ".")) { "JSON 路径无效" }
                current = if (token.groupValues[2].isNotEmpty()) {
                    (current as JSONArray).get(token.groupValues[2].toInt())
                } else when (val parent = current) {
                    is JSONObject -> parent.get(token.groupValues[1])
                    is JSONArray -> parent.get(token.groupValues[1].toInt())
                    else -> error("JSON 路径不存在")
                }
                end = token.range.last + 1
            }
            require(end == normalized.length)
            require(current is Number || current is String)
            return current.toString().toDouble().also { require(it.isFinite()) }
        }
    }
}

internal object GatewayCrypto {
    fun deriveKeys(accessKey: String, secretKey: String): Pair<ByteArray, ByteArray> {
        require(Regex("gwak_[0-9a-f]{24}").matches(accessKey)) { "GateWay AK 格式无效" }
        require(Regex("gwsk_[A-Za-z0-9_-]{43}").matches(secretKey)) { "GateWay SK 格式无效" }
        val raw = Base64.getUrlDecoder().decode(secretKey.removePrefix("gwsk_"))
        require(raw.size == 32)
        fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        val prk = hmac(accessKey.toByteArray(Charsets.US_ASCII), raw)
        fun expand(info: String) = hmac(prk, info.toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
        return expand("gw-open/v1/req") to expand("gw-open/v1/resp")
    }

    fun seal(key: ByteArray, plaintext: String, aad: String,
             iv: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes)): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun open(key: ByteArray, payload: String, aad: String): String {
        val raw = Base64.getDecoder().decode(payload)
        require(raw.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray(Charsets.US_ASCII))
        return cipher.doFinal(raw.copyOfRange(12, raw.size)).toString(Charsets.UTF_8)
    }
}

class RelayApiException(val httpCode: Int, override val message: String) : Exception(message)
