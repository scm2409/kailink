package at.d71.kailink.data.push

import android.content.Context
import at.d71.kailink.KaiLinkApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Empfängt UnifiedPush-Broadcasts des Distributors und delegiert an den
 * [PushController] der App. Filteraktionen: siehe AndroidManifest.xml.
 */
class KaiLinkPushReceiver : MessagingReceiver() {

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        graph(context).pushController.onMessage()
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        graph(context).pushController.onNewEndpoint(endpoint.url)
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        graph(context).pushController.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(context: Context, instance: String) {
        graph(context).pushController.onUnregistered()
    }

    override fun onTempUnavailable(context: Context, instance: String) {
        graph(context).pushController.onRegistrationFailed("TEMP_UNAVAILABLE")
    }

    private fun graph(context: Context) =
        (context.applicationContext as KaiLinkApp).graph
}
