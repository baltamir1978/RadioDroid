package es.altamirano.fmdroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.data.StationStore
import es.altamirano.fmdroid.playback.Connection
import es.altamirano.fmdroid.playback.LyricsState
import es.altamirano.fmdroid.playback.NowPlaying
import es.altamirano.fmdroid.playback.NowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lo que enseñan los widgets «Emisora» y «Letra». */
data class NowModel(
    val station: String? = null,
    val track: String? = null,
    val artist: String? = null,
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val busy: Boolean = false,
    val previous: String? = null,
    val current: String? = null,
    val next: String? = null,
    /** Por qué no hay letra que mostrar (buscando, no hay, instrumental…), o null si la hay. */
    val message: Int? = null,
)

data class StationTile(val id: String, val name: String, val logo: Bitmap?)

data class StationsModel(val tiles: List<StationTile> = emptyList(), val currentId: String? = null)

private const val TAG = "FMWidgets"
private const val ART_PX = 256
private const val LOGO_PX = 128
private const val MAX_TILES = 12

/**
 * Alimenta los widgets desde el motor. Cada actualización de un widget es un RemoteViews que
 * viaja al launcher y cuesta batería, así que la letra sólo se mueve cuando cambia la línea, con
 * la pantalla encendida, sonando y si hay algún widget de letra puesto. Las actualizaciones se
 * cuentan en logcat (`adb logcat -s FMWidgets`) para medir lo que gasta.
 */
class WidgetFeed(
    private val context: Context,
    private val scope: CoroutineScope,
    private val now: NowPlaying,
    private val stations: StationStore,
) {
    private val _now = MutableStateFlow(NowModel())
    val nowModel: StateFlow<NowModel> = _now.asStateFlow()
    private val _stations = MutableStateFlow(StationsModel())
    val stationsModel: StateFlow<StationsModel> = _stations.asStateFlow()

    private var artUrl: String? = null
    private var art: Bitmap? = null
    private val logos = mutableMapOf<String, Bitmap?>()
    private var ticker: Job? = null
    private var pushes = 0
    private var since = System.currentTimeMillis()

    fun start() {
        scope.launch {
            now.state.collect { s ->
                val url = s.song?.artworkUrl ?: s.station?.logoURL
                if (url != artUrl) {
                    artUrl = url
                    art = url?.let { bitmap(it, ART_PX) }
                }
                publishNow(s)
                updateTicker(s)
            }
        }
        scope.launch {
            combine(stations.stations, now.state.map { it.station?.streamURL }.distinctUntilChanged()) { list, url ->
                list to url
            }.collect { (list, url) -> publishStations(list, url) }
        }
    }

    // --- «Emisora» y «Letra» ------------------------------------------------------------------

    private fun lineIndex(s: NowState): Int? {
        val lyrics = (s.lyrics as? LyricsState.Found)?.lyrics ?: return null
        val elapsed = s.clock.elapsed(now.streamNow()) ?: return null
        return lyrics.lineIndex(elapsed)
    }

    private fun model(s: NowState): NowModel {
        val found = (s.lyrics as? LyricsState.Found)?.lyrics
        val synced = found?.synced.orEmpty()
        val i = lineIndex(s)
        val message = when {
            s.song == null -> R.string.no_song
            s.lyrics == LyricsState.Loading -> R.string.lyrics_loading
            s.lyrics == LyricsState.Failed -> R.string.lyrics_failed
            found == null -> R.string.lyrics_none
            found.instrumental -> R.string.lyrics_instrumental
            synced.isEmpty() -> R.string.lyrics_unsynced
            else -> null
        }
        fun text(k: Int?) = k?.let { synced.getOrNull(it)?.text?.ifEmpty { "♪" } }
        return NowModel(
            station = s.station?.name,
            track = s.song?.track,
            artist = s.song?.artist,
            art = art,
            playing = s.connection == Connection.PLAYING,
            busy = s.connection == Connection.CONNECTING || s.connection == Connection.RECONNECTING,
            previous = if (message == null) text(i?.minus(1)) else null,
            current = if (message == null) text(i) else null,
            // Antes de la primera línea, se anuncia la primera.
            next = if (message == null) text(if (i == null) 0 else i + 1) else null,
            message = message,
        )
    }

    private suspend fun publishNow(s: NowState) {
        val m = model(s)
        if (m == _now.value) return
        _now.value = m
        push(NowPlayingWidget(), LyricsWidget())
    }

    /** El reloj de la letra sólo corre mientras suena una letra con tiempos. */
    private fun updateTicker(s: NowState) {
        val synced = (s.lyrics as? LyricsState.Found)?.lyrics?.synced.orEmpty().isNotEmpty()
        val run = synced && s.connection == Connection.PLAYING
        if (!run) { ticker?.cancel(); ticker = null; return }
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            val power = context.getSystemService(PowerManager::class.java)
            while (isActive) {
                // Con la pantalla apagada nadie ve el widget: se espera sin gastar.
                if (!power.isInteractive || !hasLyricWidgets()) { delay(2000); continue }
                publishNow(now.state.value)
                delay(250)
            }
        }
    }

    private var widgetCheckAt = 0L
    private var widgetCheck = false

    private suspend fun hasLyricWidgets(): Boolean {
        val t = System.currentTimeMillis()
        if (t - widgetCheckAt > 30_000) {
            val manager = GlanceAppWidgetManager(context)
            widgetCheck = manager.getGlanceIds(NowPlayingWidget::class.java).isNotEmpty() ||
                manager.getGlanceIds(LyricsWidget::class.java).isNotEmpty()
            widgetCheckAt = t
        }
        return widgetCheck
    }

    // --- «Emisoras» --------------------------------------------------------------------------

    private suspend fun publishStations(list: List<Station>, currentUrl: String?) {
        val shown = list.take(MAX_TILES)
        val tiles = shown.map { s ->
            val logo = if (s.logoURL == null) null
            else logos.getOrPut(s.logoURL) { bitmap(s.logoURL, LOGO_PX) }
            StationTile(s.id, s.name, logo)
        }
        _stations.value = StationsModel(tiles, shown.firstOrNull { it.streamURL == currentUrl }?.id)
        push(StationsWidget())
    }

    // --- Comunes -----------------------------------------------------------------------------

    private suspend fun push(vararg widgets: GlanceAppWidget) {
        widgets.forEach { runCatching { it.updateAll(context) } }
        pushes++
        val t = System.currentTimeMillis()
        if (t - since >= 60_000) {
            Log.d(TAG, "$pushes actualizaciones de widgets en ${(t - since) / 1000} s")
            pushes = 0
            since = t
        }
    }

    /** Sin bitmaps de hardware: los RemoteViews no los admiten. */
    private suspend fun bitmap(url: String, px: Int): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(px).allowHardware(false).build()
        (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
    }.getOrNull()
}
