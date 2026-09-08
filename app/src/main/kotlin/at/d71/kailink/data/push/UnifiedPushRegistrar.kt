package at.d71.kailink.data.push

import android.content.Context
import at.d71.kailink.domain.push.PushRegistrationTrigger
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Android-Seite der Push-Registrierung (echte UnifiedPush-Connector-API
 * 3.3.5). Implementiert die Domänennahtstelle [PushRegistrationTrigger],
 * damit ViewModels ohne Android-Typen auskommen.
 */
class UnifiedPushRegistrar(
    private val context: Context,
    private val controller: PushController,
) : PushRegistrationTrigger {

    override fun tryRegister() {
        try {
            val distributors = UnifiedPush.getDistributors(context)
            if (distributors.isEmpty()) {
                controller.onNoDistributor()
                return
            }
            controller.onDistributorAvailable()
            UnifiedPush.register(context, INSTANCE, null, null)
        } catch (t: Throwable) {
            controller.onRegistrationFailed(t.message)
        }
    }

    fun unregister() {
        try {
            UnifiedPush.unregister(context, INSTANCE)
        } catch (t: Throwable) {
            controller.onRegistrationFailed(t.message)
        }
    }

    private companion object {
        /** PoC: genau eine Push-Instanz pro Gerät. */
        const val INSTANCE = ""
    }
}
