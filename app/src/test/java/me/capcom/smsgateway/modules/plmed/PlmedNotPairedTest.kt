package me.capcom.smsgateway.modules.plmed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PL-Med W9 (D-W9-8): wskazowka NOT_PAIRED (PlmedStatusService.isNotPaired) - z tej samej zmiennej kontaktu co
 * PLMED_SILENT (smak 'local': kontakt dowolny, takze trasy /plmed/... i udane parowanie).
 */
internal class PlmedNotPairedTest {

    private val at = 1_800_000_000_000L

    @Test
    fun notPairedOnlyWithoutContactAndPairing() {
        assertTrue(PlmedStatusService.isNotPaired(running = true, contact = null, pairedAt = null))
    }

    @Test
    fun contactRemovesHint() {
        // telefon ustawiony recznie (bez parowania), PL-Med odpytuje /plmed/status - kontakt dowolny
        assertFalse(PlmedStatusService.isNotPaired(running = true, contact = at, pairedAt = null))
    }

    @Test
    fun pairingRemovesHint() {
        assertFalse(PlmedStatusService.isNotPaired(running = true, contact = null, pairedAt = at))
        assertFalse(PlmedStatusService.isNotPaired(running = true, contact = at, pairedAt = at))
    }

    @Test
    fun noHintWhenServiceStopped() {
        assertFalse(PlmedStatusService.isNotPaired(running = false, contact = null, pairedAt = null))
    }
}
