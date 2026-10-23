package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

/** PL-Med: numery z PL-Med przychodzą jako "+" + 9 cyfr bez kodu kraju (SendSMS_SMSGate). */
internal class PlmedPhoneHelperTest {

    @Test
    fun polishNumbersFromPlmed() {
        val tests = mapOf(
            "+600123456" to "+48600123456",   // "+" + 9 cyfr bez 48 — glowny przypadek PL-Med
            "600123456" to "+48600123456",    // 9 cyfr
            "+48600123456" to "+48600123456", // pelny E.164
            "48600123456" to "+48600123456",
            "+48 600 123 456" to "+48600123456",
            "+600 123 456" to "+48600123456",
            "+123" to null,                   // za krotki
            "+60123456789" to "+60123456789", // prawdziwy numer z Malezji (11 cyfr) zostaje
        )
        tests.forEach { (t, u) ->
            assertEquals(
                "dla $t",
                u,
                try {
                    PhoneHelper.filterPhoneNumber(t, "PL")
                } catch (e: Exception) {
                    null
                }
            )
        }
    }

    @Test
    fun russianBehaviourUnchanged() {
        // zachowanie upstream dla karty RU bez zmian
        assertEquals("+79001234567", PhoneHelper.filterPhoneNumber("+79001234567", "RU"))
        assertEquals("+79001234567", PhoneHelper.filterPhoneNumber("9001234567", "RU"))
    }
}
