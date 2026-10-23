package me.capcom.smsgateway.modules.plmed

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.R
import me.capcom.smsgateway.data.dao.MessagesDao
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.modules.localserver.WebService
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get
import me.capcom.smsgateway.providers.LocalIPProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PL-Med: zbiera stan telefonu i bramki (Wi-Fi, SIM, uprawnienia, bateria,
 * ostatnie połączenie z PL-Med, historia włączeń) na potrzeby ekranu głównego
 * i endpointu GET /plmed/status.
 */
class PlmedStatusService(
    private val storage: KeyValueStorage,
    private val messagesDao: MessagesDao,
    private val localServerSettings: LocalServerSettings,
) {
    @Volatile
    private var lastRequestAt: Long? = null

    /** Wołane przy każdym uwierzytelnionym żądaniu do serwera lokalnego. */
    fun noteRequest() {
        lastRequestAt = System.currentTimeMillis()
    }

    /** Wołane przy faktycznej zmianie stanu usługi serwera lokalnego. */
    @Synchronized
    fun noteServiceState(running: Boolean) {
        val known = storage.get<Boolean>(LAST_KNOWN_RUNNING)
        if (known == running) return

        val now = System.currentTimeMillis()
        storage.set(LAST_KNOWN_RUNNING, running)
        if (running) {
            storage.set(LAST_STARTED_AT, now)
            lastRequestAt = null
        } else {
            storage.set(LAST_STOPPED_AT, now)
        }

        val history = (stateHistory + StateChange(now, running)).takeLast(HISTORY_LIMIT)
        storage.set(HISTORY, history)
    }

    val lastStartedAt: Long? get() = storage.get<Long>(LAST_STARTED_AT)
    val lastStoppedAt: Long? get() = storage.get<Long>(LAST_STOPPED_AT)
    val stateHistory: List<StateChange>
        get() = storage.get<List<StateChange>>(HISTORY) ?: emptyList()

    suspend fun snapshot(context: Context): PlmedStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val running = WebService.STATUS.value ?: false

        val wifi = isWifiConnected(context)
        val ip = try {
            LocalIPProvider(context).getIP()
        } catch (e: Throwable) {
            null
        }
        val ipValid = ip != null && !ip.startsWith("127.") && ip != "0.0.0.0"

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val airplane = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0
        val simReady = tm.simState == TelephonyManager.SIM_STATE_READY
        val mobileNetwork = !tm.networkOperator.isNullOrEmpty()

        val smsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val batteryOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } else true

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val charging = battery?.let {
            val st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val failed = try {
            messagesDao.countFailedFrom(now - DAY_MS)
        } catch (e: Throwable) {
            null
        }

        val lastReq = lastRequestAt
        val startedAt = lastStartedAt
        val problems = mutableListOf<PlmedProblem>()
        val hints = mutableListOf<PlmedProblem>()
        fun problem(code: String, text: String) = problems.add(PlmedProblem(code, text))
        fun hint(code: String, text: String) = hints.add(PlmedProblem(code, text))

        if (!running) {
            problem("SERVICE_OFF", context.getString(R.string.plmed_status_service_off))
        }
        if (!wifi) {
            problem("NO_WIFI", context.getString(R.string.plmed_status_no_wifi))
        } else if (!ipValid) {
            problem("BAD_IP", context.getString(R.string.plmed_status_bad_ip, ip ?: "-"))
        }
        if (running && wifi && ipValid) {
            val sinceStart = now - (startedAt ?: now)
            if (lastReq == null) {
                if (sinceStart > PLMED_SILENT_MS) {
                    problem(
                        "PLMED_SILENT",
                        context.getString(
                            R.string.plmed_status_plmed_silent,
                            formatDuration(context, sinceStart)
                        )
                    )
                }
            } else if (now - lastReq > PLMED_SILENT_MS) {
                problem(
                    "PLMED_SILENT",
                    context.getString(
                        R.string.plmed_status_plmed_silent,
                        formatDuration(context, now - lastReq)
                    )
                )
            }
        }
        if (running && lastReq == null && storage.get<Long>(PlmedPairingService.PAIRED_AT) == null) {
            hint("NOT_PAIRED", context.getString(R.string.plmed_status_not_paired))
        }
        if (airplane) problem("AIRPLANE", context.getString(R.string.plmed_status_airplane))
        if (!simReady) problem("NO_SIM", context.getString(R.string.plmed_status_no_sim))
        else if (!mobileNetwork && !airplane) {
            problem("NO_NETWORK", context.getString(R.string.plmed_status_no_network))
        }
        if (!smsPermission) {
            problem("NO_SMS_PERMISSION", context.getString(R.string.plmed_status_no_sms_permission))
        }
        if (!batteryOptIgnored) {
            hint("BATTERY_OPTIMIZATION", context.getString(R.string.plmed_status_battery_optimization))
        }
        if (level != null && level <= LOW_BATTERY && !charging) {
            hint("BATTERY_LOW", context.getString(R.string.plmed_status_battery_low, level))
        }
        if (failed != null && failed.count > 0) {
            hint(
                "FAILED_SENDS",
                context.getString(
                    R.string.plmed_status_failed_sends,
                    failed.count,
                    formatMoment(context, failed.lastTimestamp)
                )
            )
        }

        PlmedStatus(
            appVersion = BuildConfig.VERSION_NAME,
            now = now,
            serviceRunning = running,
            lastStartedAt = startedAt,
            lastStoppedAt = lastStoppedAt,
            stateHistory = stateHistory,
            wifiConnected = wifi,
            localIp = ip,
            port = localServerSettings.port,
            lastPlmedRequestAt = lastReq,
            airplaneMode = airplane,
            simReady = simReady,
            mobileNetwork = mobileNetwork,
            smsPermission = smsPermission,
            batteryOptimizationIgnored = batteryOptIgnored,
            batteryLevel = level,
            charging = charging,
            failedSends24h = failed?.count ?: 0,
            lastFailedSendAt = failed?.lastTimestamp?.takeIf { it > 0 },
            problems = problems,
            hints = hints,
        )
    }

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            info.isConnected && (info.type == ConnectivityManager.TYPE_WIFI
                    || info.type == ConnectivityManager.TYPE_ETHERNET)
        }
    }

    companion object {
        private const val LAST_KNOWN_RUNNING = "last_known_running"
        private const val LAST_STARTED_AT = "last_started_at"
        private const val LAST_STOPPED_AT = "last_stopped_at"
        private const val HISTORY = "state_history"
        private const val HISTORY_LIMIT = 20
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val PLMED_SILENT_MS = 2L * 60L * 1000L
        private const val LOW_BATTERY = 15

        /** "8 s" / "5 min" / "3 h" */
        fun formatDuration(context: Context, ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> context.getString(R.string.plmed_duration_seconds, s)
                s < 3600 -> context.getString(R.string.plmed_duration_minutes, s / 60)
                else -> context.getString(R.string.plmed_duration_hours, s / 3600)
            }
        }

        /** "dziś o 10:32" / "wczoraj o 21:05" / "21.10 o 08:00" */
        fun formatMoment(context: Context, at: Long): String {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            cal.timeInMillis = at
            val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            return when (that) {
                today -> context.getString(R.string.plmed_time_today, time)
                yesterday -> context.getString(R.string.plmed_time_yesterday, time)
                else -> context.getString(
                    R.string.plmed_time_date,
                    SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(at)),
                    time
                )
            }
        }
    }
}
