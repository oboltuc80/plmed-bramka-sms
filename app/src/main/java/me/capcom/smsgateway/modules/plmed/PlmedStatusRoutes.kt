package me.capcom.smsgateway.modules.plmed

import android.content.Context
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * PL-Med: GET /plmed/status — stan telefonu i bramki (wymaga uwierzytelnienia
 * jak pozostałe trasy). Osobna trasa obok /health, API upstream nietknięte.
 */
class PlmedStatusRoutes(
    private val context: Context,
    private val service: PlmedStatusService,
) {
    fun register(routing: Route) {
        routing.get("/status") {
            call.respond(service.snapshot(context))
        }
    }
}
