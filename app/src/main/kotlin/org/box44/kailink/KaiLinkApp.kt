package org.box44.kailink

import android.app.Application
import org.box44.kailink.di.AppGraph

class KaiLinkApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}