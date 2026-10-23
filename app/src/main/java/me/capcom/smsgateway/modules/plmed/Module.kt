package me.capcom.smsgateway.modules.plmed

import me.capcom.smsgateway.modules.settings.PreferencesStorage
import org.koin.dsl.module

/** PL-Med: stan bramki dla ekranu głównego i GET /plmed/status. */
val plmedModule = module {
    single {
        PlmedStatusService(
            PreferencesStorage(get(), "plmed"),
            get(),
            get(),
        )
    }
    single {
        PlmedPairingService(
            PreferencesStorage(get(), "plmed"),
            get(),
            get(),
        )
    }
}
