package me.capcom.smsgateway.modules.plmed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PL-Med: kod parowania z kodu QR albo wpisany ręcznie (PlmedPairingService.parseInput).
 * PL-Med do 2026-11-10 dokładał BOM na początku kodu QR — aplikacja odrzucała taki kod.
 */
internal class PlmedPairingInputTest {

    private val stored = "https://stored.pl-med.pl/bramka-sms"   // baza z magazynu / domyślna
    private val hub = "https://hub.pl-med.pl/bramka-sms"
    private val qr = "$hub/pair/QFVSAZ"

    @Test
    fun acceptedInputs() {
        val tests = mapOf(
            qr to (hub to "QFVSAZ"),                                 // adres z QR bez BOM
            "﻿$qr" to (hub to "QFVSAZ"),                        // BOM ze starego generatora PL-Med
            "​‌‍⁠$qr" to (hub to "QFVSAZ"),      // inne znaki niewidoczne
            " ﻿$qr\r\n" to (hub to "QFVSAZ"),                   // spacje / koniec linii wokol
            "https://pl-med.pl/pair/RSVAPW" to ("https://pl-med.pl" to "RSVAPW"),
            "ABC-DEF" to (stored to "ABCDEF"),                       // kod reczny z ekranu PL-Med
            "abcdef" to (stored to "ABCDEF"),
            "﻿abc-def" to (stored to "ABCDEF"),
        )
        tests.forEach { (t, u) ->
            assertEquals("dla ${visible(t)}", u, PlmedPairingInput.parse(t, stored))
        }
    }

    @Test
    fun rejectedInputs() {
        listOf(
            "https://evil.example.com/bramka-sms/pair/QFVSAZ",      // obcy host
            "https://pl-med.pl.evil.com/pair/QFVSAZ",
            "https://evilpl-med.pl/pair/QFVSAZ",
            "﻿https://evil.example.com/pair/QFVSAZ",
            "http://hub.pl-med.pl/bramka-sms/pair/QFVSAZ",          // bez https
            "﻿http://hub.pl-med.pl/bramka-sms/pair/QFVSAZ",
            "https://hub.pl-med.pl/bramka-sms/QFVSAZ",              // brak /pair/
            "https://hub.pl-med.pl/bramka-sms/pair/QFVSA",          // 5 znakow
            "ABCDE",
            "ABCDEFG",
            "",
            "﻿",
        ).forEach { t ->
            assertEquals("dla ${visible(t)}", null, PlmedPairingInput.parse(t, stored))
        }
    }

    @Test
    fun cleanRemovesInvisibleCharacters() {
        assertEquals(qr, PlmedPairingInput.clean("﻿$qr"))
        assertEquals("ABC-DEF", PlmedPairingInput.clean("⁠ ABC​-DEF ﻿"))
    }

    private fun visible(s: String) = s.map {
        if (it.code > 0x7E || it.code < 0x20) "<U+%04X>".format(it.code) else it.toString()
    }.joinToString("")
}
