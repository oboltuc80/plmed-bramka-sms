package me.capcom.smsgateway.modules.plmed

import java.net.URI
import java.net.URISyntaxException

/**
 * PL-Med: rozbiór kodu parowania wpisanego ręcznie albo odczytanego z kodu QR
 * (PlmedPairingService.parseInput). Czysty Kotlin/JVM — testy jednostkowe bez Androida,
 * dlatego java.net.URI zamiast android.net.Uri.
 */
internal object PlmedPairingInput {
    private const val ALLOWED_HOST = "pl-med.pl"

    /**
     * BOM i znaki niewidoczne usuwane przed analizą. PL-Med do 2026-11-10 dokładał BOM
     * na początku kodu QR (błąd generatora, poprawiony w PL-Med; stare EXE u klientów mogą zostać).
     */
    private val INVISIBLE = setOf('\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060')

    fun clean(raw: String): String = raw.filterNot { it in INVISIBLE }.trim()

    /**
     * Przyjmuje kod ("RSV-APW", "rsvapw") albo adres z kodu QR
     * ("https://hub.pl-med.pl/bramka-sms/pair/RSVAPW"). Zwraca (baza, kod) albo null;
     * dla kodu wpisanego ręcznie bazą jest [defaultBase].
     * Z QR przyjmujemy tylko https i hosty PL-Med — obcy kod QR nie może przestawić bramki.
     */
    fun parse(raw: String, defaultBase: String): Pair<String, String>? {
        val text = clean(raw)
        var base = defaultBase
        var codePart = text
        if (text.contains("://")) {
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                return null
            }
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https") return null
            if (host != ALLOWED_HOST && !host.endsWith(".$ALLOWED_HOST")) return null
            val path = uri.path ?: return null
            val idx = path.indexOf("/pair/")
            if (idx < 0) return null
            base = "https://$host" + path.substring(0, idx)
            codePart = path.substring(idx + "/pair/".length)
        }
        val code = codePart.uppercase().filter { it in PlmedPairingService.CODE_ALPHABET }
        if (code.length != PlmedPairingService.CODE_LENGTH) return null
        return base to code
    }
}
