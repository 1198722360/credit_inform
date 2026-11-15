package com.creditinform.app.data

import com.creditinform.app.network.testMonitor
import com.creditinform.app.network.RelayApiException
import org.junit.Assert.*
import org.junit.Test

class LocalMonitorStateTest {
    private val low = Result.success(RemoteMonitorTestResult(1, 200, 1.0, 1.0, true))
    private val healthy = Result.success(RemoteMonitorTestResult(1, 200, 10.0, 10.0, false))
    private val failure = Result.failure<RemoteMonitorTestResult>(RelayApiException(503, "Unavailable"))

    @Test fun `breach repeats at configured interval and recovery rearms`() {
        val config = testMonitor()
        val (first, alarm) = LocalMonitorState().checked(config, low, 1000, true)
        assertEquals(RelayAlertType.MONITOR_BREACH, alarm)
        assertNull(first.checked(config, low, 300999, true).second)
        assertEquals(RelayAlertType.MONITOR_BREACH, first.checked(config, low, 301000, true).second)
        val recovered = first.checked(config, healthy, 2000, true).first
        assertEquals(RelayAlertType.MONITOR_BREACH, recovered.checked(config, low, 3000, true).second)
    }

    @Test fun `zero repeat stays quiet across persisted restart until recovery`() {
        val config = testMonitor().copy(repeatAlertSeconds = 0)
        val first = LocalMonitorState().checked(config, low, 1000, true).first
        val restored = LocalMonitorState.fromJson(first.toJson())
        assertEquals(first, restored)
        assertNull(restored.checked(config, low, 9000000, true).second)
    }

    @Test fun `errors alarm only at threshold and success resets count`() {
        val config = testMonitor()
        var state = LocalMonitorState()
        for (i in 1..2) {
            val result = state.checked(config, failure, i * 1000L, true)
            assertNull(result.second)
            state = result.first
        }
        val third = state.checked(config, failure, 3000, true)
        assertEquals(RelayAlertType.MONITOR_ERROR, third.second)
        assertEquals(3, third.first.errors)
        assertEquals(503, third.first.httpCode)
        assertEquals(0, third.first.checked(config, healthy, 4000, true).first.errors)
    }

    @Test fun `test and disabled checks update values without consuming first alarm`() {
        val config = testMonitor()
        val (tested, alarm) = LocalMonitorState().checked(config, low, 1000, false)
        assertNull(alarm)
        assertEquals(1.0, tested.value!!, 0.0)
        assertEquals(RelayAlertType.MONITOR_BREACH, tested.checked(config, low, 2000, true).second)
    }

    @Test fun `local serialization preserves credentials rules and null body`() {
        val config = testMonitor().copy(gatewaySecretKey = "local-sk", gatewayAccessKey = "local-ak")
        val restored = localMonitorFromJson(config.toLocalJson())
        assertEquals(config.gatewaySecretKey, restored.gatewaySecretKey)
        assertEquals(config.headersJson, restored.headersJson)
        assertEquals(config.url, restored.url)
        assertEquals(config.threshold, restored.threshold, 0.0)
        assertNull(restored.body)
        assertEquals(config.id, restored.id)
    }

    @Test fun `fresh install needs no server credentials`() {
        val config = RelayConfig.freshInstall()
        assertFalse(config.enabled)
        assertTrue(config.isComplete)
        assertEquals(1, config.copy(alarmVolumePercent = -1).normalized().alarmVolumePercent)
    }
}
