package es.altamirano.fmdroid.playback

import es.altamirano.fmdroid.core.SongClock
import es.altamirano.fmdroid.core.SongLyrics
import es.altamirano.fmdroid.core.StreamTitle
import es.altamirano.fmdroid.core.Titles
import es.altamirano.fmdroid.data.History
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.data.StationMemory
import es.altamirano.fmdroid.net.ITunes
import es.altamirano.fmdroid.net.Lookup
import es.altamirano.fmdroid.net.LyricsOutcome
import es.altamirano.fmdroid.net.Lrclib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Song(
    val track: String,
    val artist: String?,
    val artworkUrl: String? = null,
    val pageUrl: String? = null,
    val historyId: String? = null,
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
)

/**
 * Una muestra del reloj del audio: a la hora de pared [wall] (s), el stream iba por [stream] (s).
 * Mientras suena, el stream avanza con la pared; parado o cargando, no. Así la letra no se
 * desfasa tras una pausa breve, un corte de red o una llamada, que ExoPlayer reanuda desde el
 * búfer, y salta al directo cuando se reconecta.
 */
data class AudioSample(val wall: Double, val stream: Double, val playing: Boolean)

fun wallNow(): Double = System.currentTimeMillis() / 1000.0

/**
 * Lo que está sonando y lo que se sabe de ello. Vive en el proceso y no en una pantalla, para
 * que la carátula, la letra y el historial sigan con la app cerrada. Lo alimenta [RadioService].
 */
class NowPlaying(
    private val scope: CoroutineScope,
    private val history: History,
    private val memory: StationMemory,
) {
    private val _state = MutableStateFlow(NowState())
    val state: StateFlow<NowState> = _state.asStateFlow()

    private val _audio = MutableStateFlow(AudioSample(wallNow(), 0.0, false))
    val audio: StateFlow<AudioSample> = _audio.asStateFlow()

    private var currentKey: String? = null
    private var seenTitleBefore = false
    private var job: Job? = null

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
        currentKey = null
        seenTitleBefore = false
        _state.update {
            NowState(station = station, connection = it.connection,
                clock = SongClock(offset = memory.lyricsOffset(station.streamURL)))
        }
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
                _state.update { it.copy(song = null, lyrics = LyricsState.None) }
            }
            seenTitleBefore = true
            return
        }
        if (title.key == currentKey) return
        currentKey = title.key
        val clock = SongClock(offset = memory.lyricsOffset(station.streamURL))
            .titleChanged(heardAt, memory.titleLag(station.streamURL), seenTitleBefore)
        seenTitleBefore = true

        val historyId = history.record(title.track, title.artist, station.name)
        _state.update {
            it.copy(song = Song(title.track, title.artist, historyId = historyId),
                lyrics = LyricsState.Loading, clock = clock)
        }
        job?.cancel()
        job = scope.launch { lookUp(title, historyId, station) }
    }

    private suspend fun lookUp(heard: StreamTitle, historyId: String?, station: Station) {
        var title = heard
        val (status, art) = ITunes.artwork(title.track, title.artist)
        if (status == Lookup.FOUND && art != null) {
            // Cassette FM manda «Título - Artista»: iTunes lo delata.
            if (Titles.isSwapped(title, art.artist)) title = title.swapped()
            if (currentKey != heard.key || _state.value.station?.streamURL != station.streamURL) return
            _state.update {
                it.copy(song = it.song?.copy(track = title.track, artist = title.artist,
                    artworkUrl = art.imageUrl, pageUrl = art.pageUrl))
            }
            if (historyId != null) history.update(historyId) {
                it.copy(title = title.track, artist = title.artist,
                    artworkUrl = art.imageUrl, pageUrl = art.pageUrl)
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

    // --- El ajuste de la letra: por emisora, se guarda ----------------------------------------

    fun nudge(seconds: Double) = changeClock { it.nudge(seconds) }
    fun resetOffset() = changeClock { it.copy(offset = 0.0) }
    fun syncToLine(lineTime: Double) = changeClock { it.syncToLine(lineTime, streamNow()) }

    private fun changeClock(f: (SongClock) -> SongClock) {
        val url = _state.value.station?.streamURL ?: return
        _state.update { it.copy(clock = f(it.clock)) }
        memory.setLyricsOffset(url, _state.value.clock.offset)
    }

    /** Cuántos segundos de canción van, para la letra. */
    fun elapsed(): Double? = _state.value.clock.elapsed(streamNow())
}
