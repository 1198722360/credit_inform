package com.creditinform.app.ui

import com.creditinform.app.data.RemoteMonitorConfig
import com.creditinform.app.data.RemoteMonitorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RemoteMonitorUiStateTest {
    @Test
    fun `merge follows config order and associates statuses by id`() {
        val configs = listOf(config(id = "second"), config(id = "first"))
        val statuses = listOf(status(id = "first"), status(id = "second"))

        val rows = mergeRemoteMonitorRows(configs, statuses)

        assertEquals(listOf("second", "first"), rows.map(RemoteMonitorRow::id))
        assertSame(configs[0], rows[0].config)
        assertSame(statuses[1], rows[0].status)
        assertSame(configs[1], rows[1].config)
        assertSame(statuses[0], rows[1].status)
    }

    @Test
    fun `merge appends orphan statuses in status order`() {
        val configured = config(id = "configured")
        val firstOrphan = status(id = "orphan-one")
        val secondOrphan = status(id = "orphan-two")

        val rows = mergeRemoteMonitorRows(
            configs = listOf(configured),
            statuses = listOf(firstOrphan, status(id = "configured"), secondOrphan)
        )

        assertEquals(
            listOf("configured", "orphan-one", "orphan-two"),
            rows.map(RemoteMonitorRow::id)
        )
        assertNull(rows[1].config)
        assertSame(firstOrphan, rows[1].status)
        assertNull(rows[2].config)
        assertSame(secondOrphan, rows[2].status)
    }

    @Test
    fun `move places a later monitor at an earlier target`() {
        val ids = listOf("one", "two", "three", "four")

        assertEquals(
            listOf("one", "four", "two", "three"),
            moveRemoteMonitorId(ids, draggedId = "four", targetId = "two")
        )
    }

    @Test
    fun `move places an earlier monitor at a later target`() {
        val ids = listOf("one", "two", "three", "four")

        assertEquals(
            listOf("two", "three", "one", "four"),
            moveRemoteMonitorId(ids, draggedId = "one", targetId = "three")
        )
    }

    @Test
    fun `move leaves order unchanged for missing or identical ids`() {
        val ids = listOf("one", "two", "three")

        assertSame(ids, moveRemoteMonitorId(ids, draggedId = "missing", targetId = "two"))
        assertSame(ids, moveRemoteMonitorId(ids, draggedId = "one", targetId = "missing"))
        assertSame(ids, moveRemoteMonitorId(ids, draggedId = "two", targetId = "two"))
    }

    @Test
    fun `successful monitor test has no inline error`() {
        assertEquals(
            "",
            monitorTestErrorMessage(
                monitorName = "Main balance",
                testError = null,
                refreshError = null
            )
        )
    }

    @Test
    fun `monitor test reports test and refresh errors inline`() {
        assertEquals(
            "Main balance 测试失败：upstream timeout\n同步最新值失败：relay timeout",
            monitorTestErrorMessage(
                monitorName = "Main balance",
                testError = "upstream timeout",
                refreshError = "relay timeout"
            )
        )
    }

    @Test
    fun `value tone uses no data and alert boundaries`() {
        assertEquals(
            MonitorValueTone.NO_DATA,
            monitorValueTone(value = null, threshold = 100.0, breached = true)
        )
        assertEquals(
            MonitorValueTone.ALERT,
            monitorValueTone(value = 100.0, threshold = 100.0, breached = false)
        )
        assertEquals(
            MonitorValueTone.ALERT,
            monitorValueTone(value = 150.0, threshold = 100.0, breached = true)
        )
    }

    @Test
    fun `value tone uses warning band through twenty percent above threshold`() {
        assertEquals(
            MonitorValueTone.WARNING,
            monitorValueTone(value = 100.0001, threshold = 100.0, breached = false)
        )
        assertEquals(
            MonitorValueTone.WARNING,
            monitorValueTone(value = 120.0, threshold = 100.0, breached = false)
        )
    }

    @Test
    fun `value tone is healthy above warning boundary`() {
        assertEquals(
            MonitorValueTone.HEALTHY,
            monitorValueTone(value = 120.0001, threshold = 100.0, breached = false)
        )
    }

    private fun config(id: String) = RemoteMonitorConfig(
        groupId = "night-team",
        id = id,
        revision = 1,
        updatedAt = 1_000,
        name = "Monitor $id",
        url = "https://example.test/$id",
        method = "GET",
        headersJson = null,
        body = null,
        jsonPath = "$.balance",
        operator = "<=",
        threshold = 100.0,
        multiplier = 1.0,
        offset = 0.0,
        pollIntervalSeconds = 60,
        timeoutSeconds = 10,
        errorThreshold = 3,
        repeatAlertSeconds = 300,
        enabled = true
    )

    private fun status(id: String) = RemoteMonitorStatus(
        id = id,
        name = "Monitor $id",
        enabled = true,
        url = "https://example.test/$id",
        method = "GET",
        operator = "<=",
        threshold = 100.0,
        pollIntervalSeconds = 60,
        lastCheckAt = 1_000,
        lastSuccessAt = 1_000,
        lastHttpCode = 200,
        lastRawValue = 150.0,
        lastValue = 150.0,
        lastError = "",
        breached = false,
        consecutiveErrors = 0,
        nextCheckAt = 1_060
    )
}
