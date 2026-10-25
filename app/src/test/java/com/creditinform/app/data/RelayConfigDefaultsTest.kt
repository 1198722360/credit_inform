package com.creditinform.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RelayConfigDefaultsTest {
    @Test
    fun `fresh install has usable relay defaults`() {
        val config = RelayConfig.freshInstall()

        assertEquals("http://70.39.195.177:8047", config.serverUrl)
        assertTrue(config.sharedToken.isNotBlank())
        assertEquals("customer-service", config.groupId)
        assertEquals("Android 客户端", config.deviceName)
        assertFalse(config.enabled)
        assertTrue(config.isComplete)
    }

    @Test
    fun `saved configs on the retired default server move to the new default`() {
        assertEquals(
            RelayConfig.DEFAULT_SERVER_URL,
            RelayConfig.migrateServerUrl("http://15.204.246.22:8047")
        )
        assertEquals(
            RelayConfig.DEFAULT_SERVER_URL,
            RelayConfig.migrateServerUrl(" http://15.204.246.22:8047/ ")
        )
        assertEquals(
            "https://relay.example.test",
            RelayConfig.migrateServerUrl("https://relay.example.test")
        )
    }

    @Test
    fun `plain config does not inject fresh install credentials`() {
        val config = RelayConfig()

        assertEquals("", config.serverUrl)
        assertEquals("", config.sharedToken)
    }

    @Test
    fun `fresh installs generate independent valid client ids`() {
        val firstId = RelayConfig.freshInstall().deviceId
        val secondId = RelayConfig.freshInstall().deviceId

        UUID.fromString(firstId)
        UUID.fromString(secondId)
        assertTrue(RelayConfig.isValidIdentifier(firstId))
        assertTrue(RelayConfig.isValidIdentifier(secondId))
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun `normalizing legacy config discards removed identity fields`() {
        val migrated = RelayConfig(
            serverUrl = " https://relay.example.test/ ",
            groupId = "legacy-team",
            sharedToken = " legacy-shared-token ",
            adminToken = " legacy-admin-token ",
            deviceId = " legacy-client-id ",
            deviceName = "Legacy support phone"
        ).normalized()

        assertEquals("customer-service", migrated.groupId)
        assertEquals("Android 客户端", migrated.deviceName)
        assertEquals("https://relay.example.test", migrated.serverUrl)
        assertEquals("legacy-shared-token", migrated.sharedToken)
        assertEquals("legacy-admin-token", migrated.adminToken)
        assertEquals("legacy-client-id", migrated.deviceId)
    }
}
