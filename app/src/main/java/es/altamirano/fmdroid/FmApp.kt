package es.altamirano.fmdroid

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import es.altamirano.fmdroid.playback.AudioTap
import es.altamirano.fmdroid.data.History
import es.altamirano.fmdroid.data.StationMemory
import es.altamirano.fmdroid.data.StationStore
import es.altamirano.fmdroid.playback.Intros
import es.altamirano.fmdroid.playback.NowPlaying
import es.altamirano.fmdroid.ui.Shortcuts
import es.altamirano.fmdroid.widget.WidgetFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class FmApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var stations: StationStore; private set
    lateinit var history: History; private set
    lateinit var memory: StationMemory; private set
    lateinit var nowPlaying: NowPlaying; private set
    lateinit var widgets: WidgetFeed; private set
    /** Lo que suena, para Shazam: lo alimenta ExoPlayer y lo lee el motor. */
    lateinit var tap: AudioTap; private set
    /** Las cuñas de entrada de las emisoras que las tienen. */
    lateinit var intros: Intros; private set

    override fun onCreate() {
        super.onCreate()
        stations = StationStore(this, scope)
        history = History(this, scope)
        memory = StationMemory(this, scope)
        nowPlaying = NowPlaying(scope, history, memory)
        tap = AudioTap(nowPlaying::streamNow).also { nowPlaying.tap = it }
        intros = Intros(scope, memory) { nowPlaying.foreground }
        widgets = WidgetFeed(this, scope, nowPlaying, stations).also { it.start() }
        scope.launch { stations.stations.collect { Shortcuts.publish(this@FmApp, it) } }
    }
}

val Context.fm: FmApp get() = applicationContext as FmApp
