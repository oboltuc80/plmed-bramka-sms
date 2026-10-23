package me.capcom.smsgateway.modules.plmed

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.modules.localserver.WebService
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get
import me.capcom.smsgateway.providers.LocalIPProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * PL-Med: parowanie telefonu z PL-Med przez host (KONTRAKT_Bramka_SMS_parowanie.md).
 *
 * Kod powstaje w PL-Med; telefon: GET /pair/{kod} → ustawia hasło i port serwera
 * lokalnego, restartuje usługę → POST /pair/{kod}/phone (IP, port, login, device_id)
 * → GET /pair/{kod}/result co 2 s do 120 s. Przez host nie idą żadne SMS-y.
 */
class PlmedPairingService(
    private val storage: KeyValueStorage,
    private val localServerSettings: LocalServerSettings,
    private val orchestrator: OrchestratorService,
) {
    class PairingException(message: String) : Exception(message)

    data class Outcome(val ok: Boolean, val message: String, val clinic: String)

    data class LastPairing(val clinic: String, val at: Long, val ok: Boolean, val message: String)

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val lastPairing: LastPairing?
        get() {
            val at = storage.get<Long>(PAIRED_AT) ?: return null
            return LastPairing(
                clinic = storage.get<String>(PAIRED_CLINIC) ?: "",
                at = at,
                ok = storage.get<Boolean>(PAIRED_OK) ?: false,
                message = storage.get<String>(PAIRED_MESSAGE) ?: "",
            )
        }

    /**
     * Przyjmuje kod ("RSV-APW", "rsvapw") albo adres z kodu QR
     * ("https://hub.pl-med.pl/bramka-sms/pair/RSVAPW"). Zwraca (baza, kod) albo null.
     * Z QR przyjmujemy tylko https i hosty PL-Med — obcy kod QR nie może przestawić bramki.
     */
    fun parseInput(raw: String): Pair<String, String>? {
        val text = raw.trim()
        var base = storage.get<String>(HOST) ?: DEFAULT_HOST
        var codePart = text
        if (text.contains("://")) {
            val uri = Uri.parse(text)
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https") return null
            if (host != ALLOWED_HOST && !host.endsWith(".$ALLOWED_HOST")) return null
            val path = uri.path ?: return null
            val idx = path.indexOf("/pair/")
            if (idx < 0) return null
            base = "https://$host" + path.substring(0, idx)
            codePart = path.substring(idx + "/pair/".length)
        }
        val code = codePart.uppercase().filter { it in CODE_ALPHABET }
        if (code.length != CODE_LENGTH) return null
        return base to code
    }

    suspend fun pair(
        context: Context,
        input: String,
        onStep: (String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        suspend fun step(text: String) = withContext(Dispatchers.Main) { onStep(text) }

        val (base, code) = parseInput(input)
            ?: throw PairingException(ctx.getString(R.string.plmed_pair_err_bad_code))

        // 1. ustawienia z PL-Med
        step(ctx.getString(R.string.plmed_pair_step_fetch))
        val info = call(ctx, Request.Builder().url("$base/pair/$code").get().build())
        val clinic = info.get("clinic")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val settings = info.getAsJsonObject("settings")
            ?: throw PairingException(ctx.getString(R.string.plmed_pair_err_bad_response))
        val password = settings.get("password")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.length >= 8 }
            ?: throw PairingException(ctx.getString(R.string.plmed_pair_err_bad_response))
        val port = settings.get("port")?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it in 1024..65535 }
            ?: 8080

        // 2. zapis i restart serwera lokalnego (hasło/port są czytane przy starcie usługi)
        step(ctx.getString(R.string.plmed_pair_step_apply))
        localServerSettings.applyPairing(password, port)
        localServerSettings.enabled = true
        restartServices(ctx, port)

        val ip = try {
            LocalIPProvider(ctx).getIP()
        } catch (e: Throwable) {
            null
        }
        if (ip == null || ip.startsWith("127.") || ip == "0.0.0.0") {
            throw PairingException(ctx.getString(R.string.plmed_pair_err_no_ip))
        }

        // 3. dane telefonu do PL-Med
        step(ctx.getString(R.string.plmed_pair_step_send))
        val phone = JsonObject().apply {
            addProperty("ip", ip)
            addProperty("port", port)
            addProperty("username", localServerSettings.username)
            addProperty("device_id", localServerSettings.deviceId ?: "")
            addProperty("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            addProperty("app_version", BuildConfig.VERSION_NAME)
        }
        call(
            ctx,
            Request.Builder().url("$base/pair/$code/phone")
                .post(phone.toString().toRequestBody(JSON))
                .build()
        )

        // 4. czekamy na wynik /health z PL-Med
        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
        var outcome: Outcome? = null
        while (outcome == null && System.currentTimeMillis() < deadline) {
            val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            step(ctx.getString(R.string.plmed_pair_step_wait, left))
            delay(RESULT_POLL_MS)
            val res = try {
                call(ctx, Request.Builder().url("$base/pair/$code/result").get().build())
            } catch (e: PairingException) {
                // wpis znikl (wygasl) zanim PL-Med odpowiedzial
                throw PairingException(ctx.getString(R.string.plmed_pair_err_expired))
            }
            val result = res.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            if (res.get("state")?.asString == "done" && result != null) {
                outcome = Outcome(
                    ok = result.get("ok")?.asBoolean ?: false,
                    message = result.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    clinic = clinic,
                )
            }
        }
        val final = outcome ?: Outcome(false, ctx.getString(R.string.plmed_pair_no_confirm), clinic)

        storage.set(HOST, base)
        storage.set(PAIRED_CLINIC, clinic)
        storage.set(PAIRED_AT, System.currentTimeMillis())
        storage.set(PAIRED_OK, final.ok)
        storage.set(PAIRED_MESSAGE, final.message)
        final
    }

    /** Zatrzymuje i uruchamia usługi jak przycisk Start, czekając aż port będzie wolny. */
    private suspend fun restartServices(ctx: Context, port: Int) {
        if (WebService.STATUS.value == true) {
            withContext(Dispatchers.Main) { orchestrator.stop(ctx) }
            waitFor(10_000) { WebService.STATUS.value != true }
        }
        waitFor(10_000) { isPortFree(port) }
        withContext(Dispatchers.Main) { orchestrator.start(ctx, false) }
        if (!waitFor(15_000) { WebService.STATUS.value == true }) {
            throw PairingException(ctx.getString(R.string.plmed_pair_err_service))
        }
        delay(500)
    }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(200)
        }
        return cond()
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun call(ctx: Context, request: Request): JsonObject {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PairingException(ctx.getString(R.string.plmed_pair_err_network))
        }
        response.use { resp ->
            val body = resp.body?.string() ?: ""
            val json = try {
                gson.fromJson(body, JsonObject::class.java)
                    ?: throw IllegalStateException()
            } catch (e: Throwable) {
                throw PairingException(ctx.getString(R.string.plmed_pair_err_bad_response))
            }
            if (!resp.isSuccessful || json.get("ok")?.asBoolean != true) {
                val error = json.get("error")?.takeIf { !it.isJsonNull }?.asString
                val message = json.get("message")?.takeIf { !it.isJsonNull }?.asString
                throw PairingException(
                    when (error) {
                        "code_invalid", "not_found" -> ctx.getString(R.string.plmed_pair_err_code_invalid)
                        "rate_limited" -> ctx.getString(R.string.plmed_pair_err_rate_limited)
                        else -> message ?: ctx.getString(R.string.plmed_pair_err_bad_response)
                    }
                )
            }
            return json
        }
    }

    companion object {
        const val DEFAULT_HOST = "https://hub.pl-med.pl/bramka-sms"
        private const val ALLOWED_HOST = "pl-med.pl"
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 6
        private const val RESULT_TIMEOUT_MS = 120_000L
        private const val RESULT_POLL_MS = 2_000L

        // klucze w magazynie "plmed" (wspolne z PlmedStatusService)
        private const val HOST = "pairing_host"
        const val PAIRED_CLINIC = "paired_clinic"
        const val PAIRED_AT = "paired_at"
        const val PAIRED_OK = "paired_ok"
        const val PAIRED_MESSAGE = "paired_message"

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
