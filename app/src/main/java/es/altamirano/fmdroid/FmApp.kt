package es.altamirano.fmdroid

import android.app.Application
import android.content.Context
import es.altamirano.fmdroid.data.History
import es.altamirano.fmdroid.data.StationMemory
import es.altamirano.fmdroid.data.StationStore
import es.altamirano.fmdroid.playback.NowPlaying
import es.altamirano.fmdroid.ui.Shortcuts
import es.altamirano.fmdroid.widget.WidgetFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FmApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var stations: StationStore; private set
    lateinit var history: History; private set
    lateinit var memory: StationMemory; private set
    lateinit var nowPlaying: NowPlaying; private set
    lateinit var widgets: WidgetFeed; private set

    override fun onCreate() {
        super.onCreate()
        stations = StationStore(this, scope)
        history = History(this, scope)
        memory = StationMemory(this, scope)
        nowPlaying = NowPlaying(scope, history, memory)
        widgets = WidgetFeed(this, scope, nowPlaying, stations).also { it.start() }
        scope.launch { stations.stations.collect { Shortcuts.publish(this@FmApp, it) } }
    }
}

val Context.fm: FmApp get() = applicationContext as FmApp
