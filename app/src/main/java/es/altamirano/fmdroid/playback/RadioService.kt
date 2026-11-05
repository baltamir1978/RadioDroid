package es.altamirano.fmdroid.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.fm
import es.altamirano.fmdroid.net.USER_AGENT
import es.altamirano.fmdroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val ROOT = "root"
private const val STATIONS = "stations"
private const val EXTRA_NAME = "fm.name"
private const val EXTRA_LOGO = "fm.logo"
internal const val PREFS = "player"
internal const val LAST_STATION = "last_station"

/** Pasada esta pausa, al seguir se vuelve al directo en vez de tirar del búfer viejo. */
private const val STALE_PAUSE_MS = 10_000L

/** La emisora como elemento de Media3: lo que ven la pantalla de bloqueo, el coche y la app. */
fun Station.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(streamURL)
    .setMediaMetadata(stationMetadata(this))
    .build()

private fun stationMetadata(s: Station) = MediaMetadata.Builder()
    .setTitle(s.name)
    .setStation(s.name)
    .setArtworkUri(s.logoURL?.toUri())
    .setIsPlayable(true)
    .setIsBrowsable(false)
    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
    .build()

/**
 * Lo que manda la app al servicio por el MediaController. La URL va en `requestMetadata`
 * porque Media3 no pasa la `localConfiguration` de un proceso a otro; el servicio la resuelve.
 */
fun Station.toRequestItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(stationMetadata(this))
    .setRequestMetadata(
        MediaItem.RequestMetadata.Builder()
            .setMediaUri(streamURL.toUri())
            .setExtras(Bundle().apply {
                putString(EXTRA_NAME, name)
                putString(EXTRA_LOGO, logoURL)
            })
            .build()
    )
    .build()

@UnstableApi
class RadioService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    /** La cara que ven el sistema y el coche: tiempo de la canción y botones de emisora. */
    private lateinit var facade: SessionPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val now by lazy { fm.nowPlaying }

    /** Las emisoras que han pasado por aquí, también las de la búsqueda, que no están en la lista. */
    private val known = mutableMapOf<String, Station>()

    // Reconexión
    private var retries = 0
    private var retryPending = false
    private var pausedAt = 0L
    private val retry = Runnable {
        retryPending = false
        if (player.playWhenReady) player.prepare()
    }

    // Reloj del audio
    private var anchor: Double? = null
    private var lastPosition = 0L
    private var lastMediaId: String? = null
    private val sampler = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 500)
        }
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            // Vuelve la red (túnel, cambio de antena): no esperar al siguiente reintento.
            handler.post { if (retryPending) { handler.removeCallbacks(retry); retry.run() } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val sources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))
        // Búfer generoso, pensado para 5G en movimiento.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .build()

        // El audio pasa por la toma de Shazam (`AudioTap`) camino del altavoz: así se reconoce
        // lo que suena sin abrir otra conexión a la emisora.
        val tap = fm.tap
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(tap)))
                .build()
        }

        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            // El foco de audio: una llamada o el asistente pausan, y se sigue solo al acabar.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Si se van el coche o los auriculares, calla en vez de seguir por el altavoz.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        facade = SessionPlayer(player, song = ::songPosition, step = ::step)
        session = MediaLibrarySession.Builder(this, facade, LibraryCallback())
            .setSessionActivity(open)
            .build()

        // La canción que suena, a la pantalla de bloqueo, la notificación y el coche.
        scope.launch {
            now.state.map { it.station to it.song }.distinctUntilChanged().collect { (station, song) ->
                if (station != null) showSong(station, song)
            }
        }
        // Shazam sitúa la canción, llega su duración o se ajusta − / +: el reloj del sistema, al día.
        scope.launch {
            now.state.map { listOf(it.song?.track, it.clock, it.songDuration) }.distinctUntilChanged().collect {
                facade.songClockChanged()
            }
        }
        // El coche ve la lista de emisoras al día.
        scope.launch {
            fm.stations.stations.collect { session.notifyChildrenChanged(STATIONS, it.size, null) }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        handler.post(sampler)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        scope.cancel()
        session.release()
        player.release()
        now.setConnection(Connection.IDLE)
        super.onDestroy()
    }

    // --- Reproductor --------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val station = known[mediaItem.mediaId] ?: fm.stations.find(mediaItem.mediaId) ?: return
            // Cambiar la carátula (`showSong`) también pasa por aquí: sólo otra emisora reinicia el reloj.
            if (mediaItem.mediaId != lastMediaId) anchor = null
            lastMediaId = mediaItem.mediaId
            now.tune(station)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_STATION, station.id).apply()
        }

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata[i]
                if (entry is IcyInfo) {
                    entry.title?.let { now.onStreamTitle(it, streamTime()) }
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retries = 0
            updateConnection()
            sample()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateConnection()
            sample()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = System.currentTimeMillis()
                handler.removeCallbacks(retry)
                retryPending = false
            } else if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > STALE_PAUSE_MS) {
                // La radio es en directo: tras una pausa larga, al directo y no al búfer viejo.
                pausedAt = 0
                handler.post { if (player.playbackState != Player.STATE_IDLE) { player.stop(); player.prepare() } }
            }
            updateConnection()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Cortes de red (5G en movimiento, túneles): se reintenta sin fin mientras se quiera oír,
            // cada vez más espaciado, y en cuanto vuelva la red.
            if (!player.playWhenReady) return
            val delay = (1000L shl retries.coerceAtMost(5)).coerceAtMost(30_000L)
            retries++
            retryPending = true
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, delay)
            updateConnection()
        }
    }

    private fun updateConnection() {
        now.setConnection(when {
            player.mediaItemCount == 0 -> Connection.IDLE
            player.isPlaying -> Connection.PLAYING
            !player.playWhenReady -> Connection.PAUSED
            retryPending || player.playerError != null -> Connection.RECONNECTING
            else -> Connection.CONNECTING
        })
    }

    /** Segundos de stream que se han oído (ver [AudioSample]). */
    private fun streamTime(): Double {
        val pos = player.currentPosition
        val wall = wallNow()
        // Una conexión nueva vuelve a empezar la posición: el stream salta al directo.
        if (anchor == null || pos < lastPosition - 1000) anchor = wall - pos / 1000.0
        lastPosition = pos
        return anchor!! + pos / 1000.0
    }

    private fun sample() {
        if (player.mediaItemCount == 0) return
        now.sampleAudio(AudioSample(wallNow(), streamTime(), player.isPlaying))
    }

    private fun showSong(station: Station, song: Song?) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (item.mediaId != station.id) return
        val meta = item.mediaMetadata.buildUpon()
            .setTitle(song?.track ?: station.name)
            .setArtist(song?.artist ?: if (song != null) station.name else null)
            .setAlbumTitle(station.name)
            .setArtworkUri((song?.artworkUrl ?: station.logoURL)?.toUri())
            .build()
        // Mismo URI: ExoPlayer cambia los datos sin cortar el sonido.
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(meta).build())
    }

    /** Por dónde va la canción y cuánto dura (ms), si se sabe: lo que ven el coche y la pantalla de bloqueo. */
    private fun songPosition(): Pair<Long, Long>? =
        now.state.value.progress(now.streamNow())?.let { (at, length) -> (at * 1000).toLong() to (length * 1000).toLong() }

    /** Los botones anterior / siguiente: la emisora de al lado en «Mis Emisoras», dando la vuelta. */
    private fun step(by: Int) {
        val list = fm.stations.stations.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == player.currentMediaItem?.mediaId }
        val next = list[if (i < 0) (if (by > 0) 0 else list.lastIndex) else (i + by).mod(list.size)]
        known[next.id] = next
        player.setMediaItem(next.toMediaItem())
        player.prepare()
        player.play()
    }

    // --- Sesión y árbol para Android Auto ----------------------------------------------------

    private fun resolve(item: MediaItem): MediaItem? {
        fm.stations.find(item.mediaId)?.let { known[it.id] = it; return it.toMediaItem() }
        known[item.mediaId]?.let { return it.toMediaItem() }
        val url = item.requestMetadata.mediaUri?.toString() ?: item.localConfiguration?.uri?.toString() ?: return null
        val extras = item.requestMetadata.extras
        val station = fm.stations.findByUrl(url) ?: Station(
            name = extras?.getString(EXTRA_NAME) ?: item.mediaMetadata.title?.toString() ?: url,
            streamURL = url,
            logoURL = extras?.getString(EXTRA_LOGO) ?: item.mediaMetadata.artworkUri?.toString(),
            id = item.mediaId.ifEmpty { url },
        )
        known[station.id] = station
        return station.toMediaItem()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        private fun folder(id: String, title: String) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true)
                .setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build())
            .build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = when (parentId) {
                ROOT -> listOf(folder(STATIONS, getString(R.string.stations_folder)))
                STATIONS -> fm.stations.stations.value.map { it.toMediaItem() }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = fm.stations.find(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(station.toMediaItem(), null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull(::resolve).toMutableList())

        /** «Seguir escuchando» del sistema y del coche: la última emisora. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val id = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_STATION, null)
            val station = id?.let { fm.stations.find(it) } ?: fm.stations.stations.value.firstOrNull()
            val items = listOfNotNull(station?.also { known[it.id] = it }?.toMediaItem())
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET))
        }
    }
}
