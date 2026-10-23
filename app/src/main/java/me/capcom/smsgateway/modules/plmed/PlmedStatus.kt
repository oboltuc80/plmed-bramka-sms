package me.capcom.smsgateway.modules.plmed

/**
 * PL-Med: stan bramki widoczny pod kartą na ekranie głównym i zwracany przez
 * GET /plmed/status (serwer lokalny, Basic Auth). Czasy w milisekundach epoki.
 */
data class StateChange(
    val at: Long,
    val running: Boolean,
)

data class PlmedProblem(
    val code: String,
    val text: String,
)

data class PlmedStatus(
    val appVersion: String,
    val now: Long,
    val serviceRunning: Boolean,
    val lastStartedAt: Long?,
    val lastStoppedAt: Long?,
    val stateHistory: List<StateChange>,
    val wifiConnected: Boolean,
    val localIp: String?,
    val port: Int,
    val lastPlmedRequestAt: Long?,
    val airplaneMode: Boolean,
    val simReady: Boolean,
    val mobileNetwork: Boolean,
    val smsPermission: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val batteryLevel: Int?,
    val charging: Boolean,
    val failedSends24h: Int,
    val lastFailedSendAt: Long?,
    /** Problemy: bramka nie bedzie dzialac (czerwone, logo ukryte). */
    val problems: List<PlmedProblem>,
    /** Wskazowki: dziala, ale warto poprawic (bursztynowe, logo widoczne). */
    val hints: List<PlmedProblem>,
) {
    val ok: Boolean get() = problems.isEmpty()
}
