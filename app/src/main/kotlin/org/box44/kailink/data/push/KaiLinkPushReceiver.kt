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
 * Android receiver of the UnifiedPush chain (see docs/features/push.md).
 *
 * `onMessage` is goAsync-guarded: the broadcast deadline is held via
 * `goAsync()` while [PushMessageHandler] performs the real push path —
 * payload parse → session restore (cold start) → sync → notification
 * resolution (SDK `NotificationClient`, room-list fallback) → render
 * ([PushNotifier]). The state machine [PushController] remains
 * responsible for endpoint/failure events; app logic and domain stay
 * Android-free.
 */
class KaiLinkPushReceiver : MessagingReceiver() {

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        val graph = graph(context) ?: return
        val pending = goAsync()
        val notifier = PushNotifier(context.applicationContext)
        graph.appScope.launch {
            try {
                graph.pushMessageHandler.handle(message.content)?.let(notifier::show)
            } catch (t: Throwable) {
                Log.w(TAG, "Push handling failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val graph = graph(context) ?: return
        // Endpoint rotation: every changed endpoint is registered again as a
        // Matrix pusher (goAsync, so the process keeps the broadcast alive
        // across the registration).
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
