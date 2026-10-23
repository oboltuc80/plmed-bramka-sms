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
    private val appContext: Context,
    private val service: PlmedStatusService,
) {
    fun register(routing: Route) {
        routing.get("/status") {
            // uwaga: w handlerze ktor 'context' to ApplicationCall, stad nazwa appContext
            call.respond(service.snapshot(appContext))
        }
    }
}
