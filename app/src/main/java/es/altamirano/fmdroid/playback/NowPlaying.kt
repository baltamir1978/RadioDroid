package es.altamirano.fmdroid.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import es.altamirano.fmdroid.R
import es.altamirano.common.shazam.ShazamSignature
import es.altamirano.fmdroid.core.SongClock
import es.altamirano.fmdroid.core.SongLyrics
import es.altamirano.fmdroid.core.StreamTitle
import es.altamirano.fmdroid.core.Titles
import es.altamirano.fmdroid.data.History
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.data.StationMemory
import es.altamirano.fmdroid.net.ITunes
import es.altamirano.fmdroid.net.LyricsOutcome
import es.altamirano.fmdroid.net.Lrclib
import es.altamirano.fmdroid.net.Shazam
import es.altamirano.fmdroid.net.ShazamOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Song(
    val track: String,
    val artist: String?,
    val artworkUrl: String? = null,
    val pageUrl: String? = null,
    val historyId: String? = null,
    /** La nombró Shazam (la emisora no manda títulos, o se quedó con el de antes). */
    val fromShazam: Boolean = false,
    /** Lo que dura según iTunes (s); LRCLIB, si tiene la letra, manda. */
    val duration: Double? = null,
)

sealed interface LyricsState {
    data object None : LyricsState
    data object Loading : LyricsState
    data class Found(val lyrics: SongLyrics) : LyricsState
    data object NotFound : LyricsState
    data object Failed : LyricsState
}

enum class Connection { IDLE, CONNECTING, PLAYING, PAUSED, RECONNECTING }

data class NowState(
    val station: Station? = null,
    val song: Song? = null,
    val lyrics: LyricsState = LyricsState.None,
    val clock: SongClock = SongClock(),
    val connection: Connection = Connection.IDLE,
    /** Preguntando a Shazam. */
    val identifying: Boolean = false,
    /** Por qué falló la última identificación pedida a mano, para decirlo. */
    val identifyMessage: Int? = null,
) {
    /** Lo que dura la canción (s): la de LRCLIB, que es la de la letra, o la de iTunes. */
    val songDuration: Double?
        get() = (lyrics as? LyricsState.Found)?.lyrics?.duration ?: song?.duration

    /**
     * Por dónde va la canción y cuánto dura (s), con [streamNow] el tiempo de stream que se oye.
     * Sólo si se sabe de verdad dónde empezó (`clock.exact`), como la letra, que tampoco resalta
     * nada hasta entonces. El ajuste − / + cuenta: corrige el retraso de la emisora.
     */
    fun progress(streamNow: Double): Pair<Double, Double>? {
        val start = clock.startedAt?.takeIf { clock.exact } ?: return null
        val length = songDuration?.takeIf { it > 0 } ?: return null
        val at = streamNow - start + clock.offset
        return if (at in 0.0..length + PROGRESS_SLACK) at.coerceAtMost(length) to length else null
    }
}

/** Se pasa de la duración un poco (títulos que llegan tarde) sin dejar de mostrar la barra. */
private const val PROGRESS_SLACK = 10.0

/**
 * Una muestra del reloj del audio: a la hora de pared [wall] (s), el stream iba por [stream] (s).
 * Mientras suena, el stream avanza con la pared; parado o cargando, no. Así la letra no se
 * desfasa tras una pausa breve, un corte de red o una llamada, que ExoPlayer reanuda desde el
 * búfer, y salta al directo cuando se reconecta.
 */
data class AudioSample(val wall: Double, val stream: Double, val playing: Boolean)

fun wallNow(): Double = System.currentTimeMillis() / 1000.0

/** Lo que pide Shazam: unos 12 s; con 8 también reconoce (medido). */
private const val CLIP_SECONDS = 12.0
private const val MIN_CLIP_SECONDS = 8.0
/** Sin título de la emisora durante esto, se pregunta a Shazam qué suena. */
private const val NO_TITLE_SECONDS = 20.0
/** Intentos por canción para situarla; luego sólo la comprobación de título que se queda. */
private const val PLACE_ATTEMPTS = 2

/**
 * Lo que está sonando y lo que se sabe de ello. Vive en el proceso y no en una pantalla, para
 * que la carátula, la letra y el historial sigan con la app cerrada. Lo alimenta [RadioService].
 */
@OptIn(UnstableApi::class)
class NowPlaying(
    private val scope: CoroutineScope,
    private val history: History,
    private val memory: StationMemory,
) {
    private val _state = MutableStateFlow(NowState())
    val state: StateFlow<NowState> = _state.asStateFlow()

    private val _audio = MutableStateFlow(AudioSample(wallNow(), 0.0, false))
    val audio: StateFlow<AudioSample> = _audio.asStateFlow()

    /** El audio que suena, para Shazam. Lo pone `FmApp`. */
    var tap: AudioTap? = null

    /**
     * La app está a la vista. Sólo entonces se identifica solo, por los datos móviles (como en
     * RadioApp); el botón funciona siempre.
     */
    @Volatile var foreground = false

    private var currentKey: String? = null
    private var seenTitleBefore = false
    private var job: Job? = null

    // Lo que se sabe de la canción actual para Shazam
    private var titleHeardAt: Double? = null      // cuándo cambió el título (tiempo de stream)
    private var titleWasChange = false            // se vio cambiar (no es el primero al sintonizar)
    private var placed = false                    // Shazam dijo por dónde va
    private var attempts = 0
    private var retryAfter = 0.0
    private var misses = 0
    private var noTitleSince: Double? = null
    private var identifyJob: Job? = null

    init {
        // Cada segundo, ¿toca preguntar a Shazam?
        scope.launch {
            while (true) {
                delay(1000)
                if (wantsAutoIdentify()) identify(manual = false)
            }
        }
    }

    /** Por dónde va el audio que se oye, en segundos de stream. */
    fun streamNow(): Double {
        val s = _audio.value
        return if (s.playing) s.stream + (wallNow() - s.wall) else s.stream
    }

    fun sampleAudio(sample: AudioSample) { _audio.value = sample }

    fun setConnection(c: Connection) = _state.update { it.copy(connection = c) }

    fun tune(station: Station) {
        if (_state.value.station?.streamURL == station.streamURL) {
            _state.update { it.copy(station = station) }   // el nombre o el logo pueden haber cambiado
            return
        }
        job?.cancel()
        identifyJob?.cancel()
        currentKey = null
        seenTitleBefore = false
        resetSongTracking()
        noTitleSince = null
        retryAfter = 0.0
        misses = 0
        _state.update {
            NowState(station = station, connection = it.connection,
                clock = SongClock(offset = memory.lyricsOffset(station.streamURL)))
        }
    }

    private fun resetSongTracking() {
        titleHeardAt = null
        titleWasChange = false
        placed = false
        attempts = 0
    }

    /** Ha llegado un título ICY, en su momento del audio ([heardAt], tiempo de stream). */
    fun onStreamTitle(raw: String, heardAt: Double) {
        val station = _state.value.station ?: return
        val title = Titles.parse(raw, station.name)
        if (title == null) {
            // Un eslogan o un código entre canciones: la anterior ya no suena.
            if (currentKey != null) {
                job?.cancel()
                currentKey = null
                resetSongTracking()
                noTitleSince = heardAt
                _state.update { it.copy(song = null, lyrics = LyricsState.None) }
            }
            seenTitleBefore = true
            return
        }
        if (title.key == currentKey) return
        currentKey = title.key
        resetSongTracking()
        noTitleSince = null
        titleHeardAt = heardAt
        titleWasChange = seenTitleBefore
        val clock = SongClock(offset = memory.lyricsOffset(station.streamURL))
            .titleChanged(heardAt, memory.titleLag(station.streamURL), seenTitleBefore)
        seenTitleBefore = true

        val historyId = history.record(title.track, title.artist, station.name)
        _state.update {
            it.copy(song = Song(title.track, title.artist, historyId = historyId),
                lyrics = LyricsState.Loading, clock = clock, identifyMessage = null)
        }
        job?.cancel()
        job = scope.launch { lookUp(title, historyId, station) }
    }

    private suspend fun lookUp(heard: StreamTitle, historyId: String?, station: Station, artwork: Boolean = true) {
        var title = heard
        if (artwork) {
            val (_, art) = ITunes.artwork(title.track, title.artist)
            if (art != null) {
                // Cassette FM manda «Título - Artista»: iTunes lo delata.
                if (Titles.isSwapped(title, art.artist)) title = title.swapped()
                if (_state.value.station?.streamURL != station.streamURL) return
                _state.update {
                    val s = it.song ?: return@update it
                    if (s.historyId != historyId) it
                    else it.copy(song = s.copy(track = title.track, artist = title.artist,
                        artworkUrl = s.artworkUrl ?: art.imageUrl, pageUrl = s.pageUrl ?: art.pageUrl,
                        duration = s.duration ?: art.duration))
                }
                if (historyId != null) history.update(historyId) {
                    it.copy(title = title.track, artist = title.artist,
                        artworkUrl = it.artworkUrl ?: art.imageUrl, pageUrl = it.pageUrl ?: art.pageUrl)
                }
            }
        }
        loadLyrics(title)
    }

    private suspend fun loadLyrics(title: StreamTitle) {
        val result = when (val outcome = Lrclib.lookup(title.track, title.artist)) {
            is LyricsOutcome.Found -> LyricsState.Found(outcome.lyrics)
            LyricsOutcome.NotFound -> LyricsState.NotFound
            LyricsOutcome.Failed -> LyricsState.Failed
        }
        _state.update { if (it.song?.track == title.track) it.copy(lyrics = result) else it }
    }

    fun retryLyrics() {
        val song = _state.value.song ?: return
        _state.update { it.copy(lyrics = LyricsState.Loading) }
        job?.cancel()
        job = scope.launch { loadLyrics(StreamTitle(song.track, song.artist)) }
    }

    // --- Shazam ---------------------------------------------------------------------------------

    private fun wantsAutoIdentify(): Boolean {
        val s = _state.value
        val tap = tap ?: return false
        if (!foreground || s.connection != Connection.PLAYING) return false
        if (identifyJob?.isActive == true) return false
        val now = streamNow()
        if (now < retryAfter || tap.seconds < CLIP_SECONDS) return false

        val song = s.song
        if (song == null) {
            // La emisora no dice qué suena (no manda títulos, o va un eslogan): que lo diga Shazam.
            val since = noTitleSince ?: now.also { noTitleSince = it }
            return now - since >= NO_TITLE_SECONDS
        }
        // Situar la canción: con 12 s de audio desde que cambió el título (antes es la anterior).
        if (!placed && attempts < PLACE_ATTEMPTS) {
            val from = if (titleWasChange) titleHeardAt ?: now else now - CLIP_SECONDS
            if (tap.endTime - from >= CLIP_SECONDS) return true
        }
        // ¿Se ha quedado el título? Pasada la duración más 20 s, se pregunta.
        val duration = ((s.lyrics as? LyricsState.Found)?.lyrics)?.duration
        val deadline = s.clock.staleDeadline(duration) ?: return false
        return now > deadline
    }

    /** El botón «Identificar canción». */
    fun identifyNow() = identify(manual = true)

    private fun identify(manual: Boolean) {
        if (identifyJob?.isActive == true) return
        val tap = tap ?: return
        val station = _state.value.station ?: return
        identifyJob = scope.launch {
            _state.update { it.copy(identifying = true, identifyMessage = null) }
            try {
                // A mano, recién sintonizado: esperar a tener audio suficiente (hasta 12 s).
                var waited = 0
                while (tap.seconds < MIN_CLIP_SECONDS && waited < 12) { delay(1000); waited++ }
                var (pcm, startAt) = tap.snapshot(CLIP_SECONDS) ?: return@launch fail(manual)
                // Para situar una canción que acaba de cambiar, sólo el audio desde el cambio.
                val heard = titleHeardAt
                if (!manual && !placed && titleWasChange && heard != null && startAt < heard) {
                    val drop = ((heard - startAt) * ShazamSignature.SAMPLE_RATE).toInt()
                    if (pcm.size - drop >= MIN_CLIP_SECONDS * ShazamSignature.SAMPLE_RATE) {
                        pcm = pcm.copyOfRange(drop, pcm.size)
                        startAt = heard
                    }
                }
                if (pcm.size < MIN_CLIP_SECONDS * ShazamSignature.SAMPLE_RATE) return@launch fail(manual)
                attempts++
                val signature = withContext(Dispatchers.Default) { ShazamSignature.of(pcm) }
                val outcome = Shazam.recognize(signature)
                if (_state.value.station?.streamURL != station.streamURL) return@launch
                when (outcome) {
                    is ShazamOutcome.Match -> matched(outcome, startAt, station)
                    ShazamOutcome.NoMatch -> missed(manual, R.string.no_match_found)
                    ShazamOutcome.Failed -> missed(manual, R.string.identify_failed)
                }
            } finally {
                _state.update { it.copy(identifying = false) }
            }
        }
    }

    private fun fail(manual: Boolean) = missed(manual, R.string.no_match_found)

    /** Sin respuesta: se espera cada vez más (30 s, 1, 2, 4 min, hasta 5) antes de volver a preguntar. */
    private fun missed(manual: Boolean, message: Int) {
        misses++
        retryAfter = streamNow() + minOf(30.0 * (1 shl (misses - 1).coerceAtMost(4)), 300.0)
        if (manual) _state.update { it.copy(identifyMessage = message) }
    }

    private fun matched(m: ShazamOutcome.Match, sampleStart: Double, station: Station) {
        misses = 0
        retryAfter = 0.0
        val songStart = sampleStart - m.offset
        val current = _state.value.song
        val same = current != null && (Titles.sameSong(current.track, m.title) ||
            (current.artist != null && Titles.sameSong(current.artist, m.title)))   // venía al revés

        if (same && current != null) {
            // Es la que dice la emisora: ahora se sabe por dónde va, y cuánto tarda la emisora
            // en cambiar el título (sólo si se le vio cambiar).
            val heard = titleHeardAt
            if (!current.fromShazam && titleWasChange && heard != null) {
                memory.learnTitleLag(station.streamURL, heard - songStart)
            }
            placed = true
            _state.update {
                it.copy(
                    // Con Shazam situando la canción, el ajuste manual vuelve a ±0.
                    clock = SongClock(startedAt = songStart, exact = true, offset = 0.0),
                    song = it.song?.copy(artworkUrl = it.song.artworkUrl ?: m.coverArt),
                )
            }
            return
        }

        // Otra canción: la emisora no manda títulos o se quedó con el de antes. Shazam la nombra
        // hasta que la emisora cambie el título.
        val historyId = history.record(m.title, m.artist, station.name)
        if (historyId != null) history.update(historyId) {
            it.copy(artworkUrl = it.artworkUrl ?: m.coverArt, pageUrl = it.pageUrl ?: m.pageUrl)
        }
        placed = true
        attempts = 0
        titleHeardAt = null
        titleWasChange = false
        noTitleSince = null
        _state.update {
            it.copy(
                song = Song(m.title, m.artist, m.coverArt, m.pageUrl, historyId, fromShazam = true),
                lyrics = LyricsState.Loading,
                clock = SongClock(startedAt = songStart, exact = true, offset = 0.0),
            )
        }
        job?.cancel()
        job = scope.launch { lookUp(StreamTitle(m.title, m.artist), historyId, station, artwork = m.coverArt == null) }
    }

    // --- El ajuste de la letra ----------------------------------------------------------------

    fun nudge(seconds: Double) = changeClock { it.nudge(seconds) }
    fun resetOffset() = changeClock { it.copy(offset = 0.0) }
    fun syncToLine(lineTime: Double) = changeClock { it.syncToLine(lineTime, streamNow()) }

    /**
     * El ajuste es de la emisora y se guarda, salvo si Shazam situó la canción: entonces es de
     * esa canción y la siguiente vuelve a ±0.
     */
    private fun changeClock(f: (SongClock) -> SongClock) {
        val url = _state.value.station?.streamURL ?: return
        _state.update { it.copy(clock = f(it.clock)) }
        if (!placed) memory.setLyricsOffset(url, _state.value.clock.offset)
    }

    /** Cuántos segundos de canción van, para la letra. */
    fun elapsed(): Double? = _state.value.clock.elapsed(streamNow())
}
