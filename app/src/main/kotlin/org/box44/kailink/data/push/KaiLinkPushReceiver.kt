package org.box44.kailink.data.push

import android.content.Context
import android.util.Log
import org.box44.kailink.KaiLinkApp
import org.box44.kailink.di.AppGraph
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Android-Empfänger der UnifiedPush-Kette (siehe docs/features/push.md).
 *
 * `onMessage` läuft goAsync-gesichert: Die Broadcast-Frist wird über
 * `goAsync()` gehalten, während eine Coroutine den echten Sync ausführt
 * (`ChannelClient.syncOnce()`), danach aus der zuletzt synchronisierten
 * Raumliste eine Benachrichtigung rendert ([PushNotifier] mit reinem
 * [PushNotificationPayload]). Der Zustandsautomat [PushController] bleibt
 * für Endpoint-/Fehlerereignisse zuständig; App-Logik und Domäne bleiben
 * Android-frei.
 */
class KaiLinkPushReceiver : MessagingReceiver() {

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val graph = graph(context) ?: return
        val pending = goAsync()
        val notifier = PushNotifier(context.applicationContext)
        graph.appScope.launch {
            try {
                val client = graph.channelClient
                client.syncOnce()
                val rooms = runCatching { client.rooms() }
                    .onFailure { Log.w(TAG, "Raumliste nach Push-Sync fehlgeschlagen: ${it.message}") }
                    .getOrDefault(emptyList())
                PushNotificationPayload.fromLatest(rooms)?.let(notifier::show)
            } catch (t: Throwable) {
                Log.w(TAG, "Push-Sync fehlgeschlagen: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val graph = graph(context) ?: return
        // Endpoint-Rotation: jeder geänderte Endpoint wird erneut als
        // Matrix-Pusher registriert (goAsync, damit der Prozess den Broadcast
        // über die Registrierung hinweg behält).
        val pending = goAsync()
        graph.appScope.launch {
            try {
                graph.pushController.onNewEndpoint(endpoint.url)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        graph(context)?.pushController?.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(context: Context, instance: String) {
        graph(context)?.pushController?.onUnregistered()
    }

    private fun graph(context: Context): AppGraph? =
        (context.applicationContext as? KaiLinkApp)?.graph

    private companion object {
        const val TAG = "KaiLinkPush"
    }
}
