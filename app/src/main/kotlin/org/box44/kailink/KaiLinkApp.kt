package org.box44.kailink

import android.app.Application
import org.box44.kailink.data.push.PushNotifier
import org.box44.kailink.di.AppGraph

class KaiLinkApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Benachrichtigungskanal früh anlegen (idempotent), damit er in den
        // Systemeinstellungen erscheint, bevor der erste Push eintrifft.
        PushNotifier(this).ensureChannel()
    }
}
