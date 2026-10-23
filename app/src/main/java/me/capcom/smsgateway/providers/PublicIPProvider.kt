package me.capcom.smsgateway.providers

import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.modules.gateway.GatewayService
import org.koin.java.KoinJavaComponent.inject

class PublicIPProvider: IPProvider {
    private val gatewaySvc by inject<GatewayService>(GatewayService::class.java)

    override suspend fun getIP(): String? {
        // PL-Med: adres publiczny pochodzi z api.sms-gate.app -- w smaku 'local' nie odpytujemy serwera upstream
        if (!BuildConfig.CLOUD_ENABLED) return null
        return try {
            gatewaySvc.getPublicIP()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}