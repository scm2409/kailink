package org.box44.kailink.data.push

import android.content.Context
import org.box44.kailink.KaiLinkApp
import org.box44.kailink.di.AppGraph
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Android-Empfänger der UnifiedPush-Kette (siehe docs/features/push.md).
 *
 * Übersetzt die Connector-Broadcasts in den JVM-getesteten Zustandsautomaten
 * [PushController]; App-Logik und Domäne bleiben Android-frei. Der Receiver
 * ist der einzige neue Android-Einstiegspunkt neben der Activity.
 */
class KaiLinkPushReceiver : MessagingReceiver() {

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        graph(context)?.pushController?.onMessage()
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        graph(context)?.pushController?.onNewEndpoint(endpoint.url)
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        graph(context)?.pushController?.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(context: Context, instance: String) {
        graph(context)?.pushController?.onUnregistered()
    }

    private fun graph(context: Context): AppGraph? =
        (context.applicationContext as? KaiLinkApp)?.graph
}
