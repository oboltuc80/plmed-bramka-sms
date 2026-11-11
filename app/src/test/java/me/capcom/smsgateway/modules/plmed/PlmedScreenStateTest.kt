package me.capcom.smsgateway.modules.plmed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PL-Med W9-2: warunek stanu "Polaczono z PL-Med" na ekranie Start (PlmedStatusService.ScreenState.connected).
 */
internal class PlmedScreenStateTest {

    private val now = 1_800_000_000_000L
    private val twoMin = 2L * 60L * 1000L

    private fun status(
        problems: List<PlmedProblem> = emptyList(),
        hints: List<PlmedProblem> = emptyList(),
    ) = PlmedStatus(
        appVersion = "test",
        deviceId = null,
        model = "test",
        now = now,
        serviceRunning = true,
        lastStartedAt = now - 10 * twoMin,
        lastStoppedAt = null,
        stateHistory = emptyList(),
        wifiConnected = true,
        localIp = "192.168.1.20",
        port = 8080,
        lastPlmedRequestAt = null,
        airplaneMode = false,
        simReady = true,
        mobileNetwork = true,
        smsPermission = true,
        batteryOptimizationIgnored = true,
        batteryLevel = 80,
        charging = true,
        failedSends24h = 0,
        lastFailedSendAt = null,
        problems = problems,
        hints = hints,
    )

    private fun state(contactAt: Long?, status: PlmedStatus = status()) =
        PlmedStatusService.ScreenState(status, contactAt)

    @Test
    fun connectedWithFreshContact() {
        assertTrue(state(now - 8_000).connected)
        assertTrue(state(now).connected)
        assertTrue("granica 2 min jak PLMED_SILENT", state(now - twoMin).connected)
    }

    @Test
    fun otherHintsDoNotMatter() {
        val s = status(hints = listOf(PlmedProblem("BATTERY_LOW", "x"), PlmedProblem("FAILED_SENDS", "y")))
        assertTrue(state(now - 8_000, s).connected)
    }

    @Test
    fun notConnectedWithoutFreshContact() {
        assertFalse("brak kontaktu", state(null).connected)
        assertFalse("kontakt starszy niz 2 min", state(now - twoMin - 1).connected)
    }

    @Test
    fun notConnectedWhenNotPaired() {
        val s = status(hints = listOf(PlmedProblem("NOT_PAIRED", "x")))
        assertFalse(state(now - 8_000, s).connected)
    }

    @Test
    fun notConnectedWithProblems() {
        val s = status(problems = listOf(PlmedProblem("NO_SIM", "x")))
        assertFalse(state(now - 8_000, s).connected)
    }
}
