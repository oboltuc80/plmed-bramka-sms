package me.capcom.smsgateway.services

import android.content.Context

/**
 * PL-Med, smak "local": zaslepka uslugi FCM. Prawdziwa implementacja (FirebaseMessagingService)
 * jest w src/cloud. W tym smaku GatewayService nigdy nie startuje (CLOUD_ENABLED = false),
 * wiec register() nie zostanie wywolane; istnieje tylko po to, zeby wspolny kod sie kompilowal.
 */
class PushService {
    companion object {
        fun register(context: Context) {
            // brak FCM w smaku local
        }
    }
}
