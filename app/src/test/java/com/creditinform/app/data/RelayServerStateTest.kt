package com.creditinform.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayServerStateTest {
    @Test
    fun `new client binds instance and starts at registration cursor`() {
        val decision = decideRelayServerState(
            storedInstanceId = null,
            incomingInstanceId = "instance-a",
            cursor = 0,
            serverCursor = 12,
            cursorInitialized = false,
            cursorTokenPresent = false,
            serverCursorTokenPresent = true,
            cursorValid = true,
            initializeIfNeeded = true,
            checkCursorRegression = true
        )

        assertTrue(decision.changed)
        assertFalse(decision.resetCursor)
        assertEquals(12L, decision.cursor)
        assertTrue(decision.cursorInitialized)
        assertEquals("instance-a", decision.serverInstanceId)
    }

    @Test
    fun `legacy upgrade replays when it first binds a server instance`() {
        val advancedServer = decideRelayServerState(
            storedInstanceId = null,
            incomingInstanceId = "instance-a",
            cursor = 10,
            serverCursor = 15,
            cursorInitialized = true,
            cursorTokenPresent = true,
            serverCursorTokenPresent = true,
            cursorValid = true,
            initializeIfNeeded = true,
            checkCursorRegression = true
        )
        val regressed = decideRelayServerState(
            storedInstanceId = null,
            incomingInstanceId = "instance-a",
            cursor = 10,
            serverCursor = 3,
            cursorInitialized = true,
            cursorTokenPresent = true,
            serverCursorTokenPresent = true,
            cursorValid = true,
            initializeIfNeeded = true,
            checkCursorRegression = true
        )

        assertTrue(advancedServer.resetCursor)
        assertEquals(0L, advancedServer.cursor)
        assertTrue(regressed.resetCursor)
        assertEquals(0L, regressed.cursor)
    }

    @Test
    fun `instance replacement and legacy downgrade reset cursor`() {
        val replacement = decideRelayServerState(
            storedInstanceId = "instance-a",
            incomingInstanceId = "instance-b",
            cursor = 20,
            serverCursor = 8,
            cursorInitialized = true,
            cursorTokenPresent = true,
            serverCursorTokenPresent = true,
            cursorValid = true,
            initializeIfNeeded = true,
            checkCursorRegression = true
        )
        val downgrade = decideRelayServerState(
            storedInstanceId = "instance-a",
            incomingInstanceId = null,
            cursor = 20,
            serverCursor = 20,
            cursorInitialized = true,
            cursorTokenPresent = true,
            serverCursorTokenPresent = false,
            cursorValid = true,
            initializeIfNeeded = false,
            checkCursorRegression = true
        )

        assertTrue(replacement.resetCursor)
        assertEquals(0L, replacement.cursor)
        assertEquals("instance-b", replacement.serverInstanceId)
        assertTrue(downgrade.resetCursor)
        assertEquals(0L, downgrade.cursor)
        assertEquals(null, downgrade.serverInstanceId)
    }

    @Test
    fun `invalid cursor token resets even when server cursor has not regressed`() {
        val decision = decideRelayServerState(
            storedInstanceId = "instance-a",
            incomingInstanceId = "instance-a",
            cursor = 20,
            serverCursor = 20,
            cursorInitialized = true,
            cursorTokenPresent = true,
            serverCursorTokenPresent = true,
            cursorValid = false,
            initializeIfNeeded = false,
            checkCursorRegression = true
        )

        assertTrue(decision.changed)
        assertTrue(decision.resetCursor)
        assertEquals(0L, decision.cursor)
        assertTrue(decision.cursorInitialized)
    }

    @Test
    fun `legacy initialized cursor resets when server advertises cursor tokens`() {
        val decision = decideRelayServerState(
            storedInstanceId = "instance-a",
            incomingInstanceId = "instance-a",
            cursor = 20,
            serverCursor = 20,
            cursorInitialized = true,
            cursorTokenPresent = false,
            serverCursorTokenPresent = true,
            cursorValid = true,
            initializeIfNeeded = true,
            checkCursorRegression = true
        )

        assertTrue(decision.changed)
        assertTrue(decision.resetCursor)
        assertEquals(0L, decision.cursor)
        assertTrue(decision.cursorInitialized)
    }

    @Test
    fun `legacy initialized cursor stays compatible with a tokenless server`() {
        val decision = decideRelayServerState(
            storedInstanceId = "instance-a",
            incomingInstanceId = "instance-a",
            cursor = 20,
            serverCursor = 20,
            cursorInitialized = true,
            cursorTokenPresent = false,
            serverCursorTokenPresent = false,
            cursorValid = true,
            initializeIfNeeded = true,
            checkCursorRegression = true
        )

        assertFalse(decision.changed)
        assertFalse(decision.resetCursor)
        assertEquals(20L, decision.cursor)
        assertTrue(decision.cursorInitialized)
    }
}
