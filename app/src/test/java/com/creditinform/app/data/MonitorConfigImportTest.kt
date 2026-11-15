package com.creditinform.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MonitorConfigImportTest {
    private fun monitor() = JSONObject().put("id", "balance").put("name", "余额")
        .put("url", "https://example.com/balance").put("jsonPath", "data.balance").put("threshold", 10)
    private fun document(vararg items: JSONObject) = JSONObject().put("monitors", JSONArray(items.toList())).toString()

    @Test fun `imports server shaped configuration and preserves monitor order`() {
        val first = monitor().put("headers", JSONObject().put("Authorization", "Bearer abc"))
        val second = monitor().put("id", "backup").put("method", "POST").put("body", JSONObject().put("account", "backup")).put("enabled", false)
        val imported = MonitorConfigImport.parse(document(first, second))
        assertEquals(listOf("balance", "backup"), imported.map { it.id })
        assertEquals("Bearer abc", JSONObject(imported[0].headersJson!!).getString("Authorization"))
        assertEquals("backup", JSONObject(imported[1].body!!).getString("account"))
        assertFalse(imported[1].enabled)
        assertEquals(60L, imported[0].pollIntervalSeconds)
    }

    @Test fun `accepts bom bare array generated ids and local serialized config`() {
        val source = monitor().apply { remove("id") }
        val imported = MonitorConfigImport.parse("\uFEFF" + JSONArray().put(source))
        assertTrue(imported.single().id.isNotBlank())
        val roundTrip = MonitorConfigImport.parse(document(imported.single().toLocalJson())).single()
        assertEquals(imported.single().id, roundTrip.id)
        assertEquals(imported.single().url, roundTrip.url)
    }

    @Test fun `rejects complete file when any entry is invalid or duplicated`() {
        for (bad in listOf(monitor(), monitor().put("id", "bad").put("threshold", "NaN"),
            monitor().put("id", "bad").put("pollIntervalSeconds", 1.5),
            monitor().put("id", "bad").put("enabled", "false"),
            monitor().put("id", "bad").put("timeoutSeconds", 4294967297L))) {
            assertTrue(runCatching { MonitorConfigImport.parse(document(monitor(), bad)) }.isFailure)
        }
    }

    @Test fun `requires explicit monitor array before allowing empty replacement`() {
        assertTrue(MonitorConfigImport.parse("{\"monitors\":[]}").isEmpty())
        for (text in listOf("{}", "", "null", "{\"monitors\":{}}", document(monitor()) + " extra")) {
            assertTrue(runCatching { MonitorConfigImport.parse(text) }.isFailure)
        }
    }

    @Test fun `gateway needs actual key rather than server environment reference`() {
        val gateway = monitor().put("provider", "gateway").put("url", "https://op.privapi.cc/open/v1/balance/query")
            .put("gatewayAccessKey", "gwak_7c3e910a5bd24418e62f83c9").put("gatewaySecretKeyEnv", "GATEWAY_SK")
        assertTrue(runCatching { MonitorConfigImport.parse(document(gateway)) }.isFailure)
        gateway.put("gatewaySecretKey", "gwsk_EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8")
        assertEquals("POST", MonitorConfigImport.parse(document(gateway)).single().method)
    }

    @Test fun `rejects placeholders and malformed fields without echoing secrets`() {
        val bad = monitor().put("headersJson", "secret-token-not-json")
        val error = runCatching { MonitorConfigImport.parse(document(bad)) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret-token"))
        assertTrue(runCatching { MonitorConfigImport.parse(document(monitor().put("headers", JSONObject().put("Authorization", "__KEEP__")))) }.isFailure)
    }

    @Test fun `bounded reader rejects oversized files and invalid utf8`() {
        assertEquals("配置", MonitorConfigImport.read("配置".byteInputStream()))
        assertTrue(runCatching { MonitorConfigImport.read(ByteArray(MonitorConfigImport.MAX_BYTES + 1).inputStream()) }.isFailure)
        assertTrue(runCatching { MonitorConfigImport.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream()) }.isFailure)
    }

    @Test fun `rejects too many monitors`() {
        val array = JSONArray()
        repeat(501) { array.put(monitor().put("id", "item-$it")) }
        assertTrue(runCatching { MonitorConfigImport.parse(array.toString()) }.isFailure)
    }
}
