package com.creditinform.app.monitoring

import com.creditinform.app.data.RelayConfig
import com.creditinform.app.data.RelayAlertType
import com.creditinform.app.data.RelayAlarmEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {
    @Test
    fun `relay config normalizes server settings and alarm safety values`() {
        val config = RelayConfig(
            serverUrl = " https://alarm.example.com/ ",
            groupId = " night-ops ",
            sharedToken = " secret ",
            adminToken = " admin-secret ",
            deviceId = " owner-phone ",
            deviceName = " owner ",
            alarmVolumePercent = 0
        ).normalized()

        assertEquals("https://alarm.example.com", config.serverUrl)
        assertEquals(RelayConfig.INTERNAL_GROUP_ID, config.groupId)
        assertEquals("secret", config.sharedToken)
        assertEquals("admin-secret", config.adminToken)
        assertEquals("owner-phone", config.deviceId)
        assertEquals(RelayConfig.INTERNAL_DEVICE_NAME, config.deviceName)
        assertEquals(1, config.alarmVolumePercent)
        assertTrue(config.isComplete)
    }

    @Test
    fun `unknown relay event types remain forward compatible`() {
        assertEquals(RelayAlertType.MANUAL, RelayAlertType.fromWire("manual"))
        assertEquals(RelayAlertType.MONITOR_BREACH, RelayAlertType.fromWire("monitor_breach"))
        assertEquals(RelayAlertType.UNKNOWN, RelayAlertType.fromWire("future-alert-type"))
    }

    @Test
    fun `relay acknowledgement keys are namespaced by server instance`() {
        val instanceA = alarmEvent(serverInstanceId = "A")
        val instanceB = alarmEvent(serverInstanceId = "B")

        assertEquals("A:7", instanceA.acknowledgementKey)
        assertEquals("B:7", instanceB.acknowledgementKey)
        assertTrue(instanceA.matchesAcknowledgementKey("A:7"))
        assertTrue(instanceB.matchesAcknowledgementKey("B:7"))
        assertFalse(instanceA.matchesAcknowledgementKey("B:7"))
        assertFalse(instanceB.matchesAcknowledgementKey("A:7"))
        assertFalse(instanceA.matchesAcknowledgementKey("7"))
        assertFalse(instanceB.matchesAcknowledgementKey("7"))
    }

    @Test
    fun `legacy bare acknowledgement key only matches events without an instance`() {
        val legacyEvent = alarmEvent(serverInstanceId = null)

        assertEquals("7", legacyEvent.acknowledgementKey)
        assertTrue(legacyEvent.matchesAcknowledgementKey("7"))
        assertTrue(legacyEvent.matchesAcknowledgementKey(":7"))
        assertFalse(legacyEvent.matchesAcknowledgementKey("A:7"))
        assertFalse(legacyEvent.matchesAcknowledgementKey("8"))
    }

    @Test
    fun `event uid separates reused numeric ids after a server rollback`() {
        val beforeRollback = alarmEvent(serverInstanceId = "A", eventUid = "before")
        val afterRollback = alarmEvent(serverInstanceId = "A", eventUid = "after")

        assertEquals("uid:before", beforeRollback.acknowledgementKey)
        assertEquals("uid:after", afterRollback.acknowledgementKey)
        assertFalse(afterRollback.matchesAcknowledgementKey(beforeRollback.acknowledgementKey))
        assertTrue(afterRollback.matchesAcknowledgementKey("uid:after"))
    }

    private fun alarmEvent(serverInstanceId: String?, eventUid: String? = null) = RelayAlarmEvent(
        eventId = 7,
        type = RelayAlertType.MANUAL,
        groupId = "night-team",
        senderId = "customer-service",
        senderName = "Customer service",
        message = "Wake up",
        createdAt = 123,
        eventUid = eventUid,
        serverInstanceId = serverInstanceId
    )
}
