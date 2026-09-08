package at.d71.kailink

import android.app.Application
import at.d71.kailink.di.AppGraph

class KaiLinkApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}