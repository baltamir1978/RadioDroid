package es.altamirano.fmdroid.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}

/** Un JSON en `filesDir`, escrito de golpe (a un temporal y renombrado) para no dejarlo a medias. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val scope: CoroutineScope) {
    private val lock = Mutex()

    fun exists() = file.exists()

    fun read(): T? = try {
        if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun write(value: T) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(AppJson.encodeToString(serializer, value))
                tmp.renameTo(file)
            }
        }
    }
}

// --- Emisoras ----------------------------------------------------------------------------------

/** Mismas claves que la `Station` de MacRadio y LinRadio, para importar su lista tal cual. */
@Serializable
data class Station(
    val name: String,
    val streamURL: String,
    val logoURL: String? = null,
    val country: String? = null,
    val genre: String? = null,
    val stationuuid: String? = null,
    val id: String = UUID.randomUUID().toString().uppercase(),
) {
    /** Género y país, para la segunda línea bajo el nombre. */
    val detail: String
        get() = listOfNotNull(genre?.replaceFirstChar { it.uppercase() }, country)
            .filter { it.isNotBlank() }.joinToString(" · ")
}

/** La misma lista de partida que RadioApp, MacRadio y LinRadio. */
val DEFAULT_STATIONS = listOf(
    Station("Vive Segovia", "https://streaming.viveradio.es/vivesegovia",
        "https://viveradio.es/dist/images/favicons/apple-touch-icon.webp", "ES", "Regional"),
    Station("Cassette FM", "https://stream.costafm.es/listen/cassettefm_-_em/radio.mp3",
        "https://media.emisorasmusicales.net/wp-content/uploads/2021/05/11022133/Cassette.png", "ES", "Pop"),
    Station("Cadena 100", "https://cadena100-cope-rrcast.flumotion.com/cope/cadena100-low.mp3",
        "https://www.cadena100.es/estaticos/apple-touch-icon-192x192.png", "ES", "Pop"),
    Station("Kiss FM", "https://bbkissfm.kissfmradio.cires21.com/bbkissfm.mp3",
        "https://www.kissfm.es/wp-content/uploads/2024/10/Logo-KISS-FM_Negativo-Color.png", "ES", "Dance"),
    Station("La Indie", "https://stream.emisorasmusicales.net/listen/la_indie/laindie.mp3",
        "https://media.emisorasmusicales.net/wp-content/uploads/2020/04/11023707/LA-INDIE.png", "ES", "Indie"),
    Station("Los 40 Classic",
        "http://playerservices.streamtheworld.com/api/livestream-redirect/LOS40_CLASSIC.mp3",
        "https://los40es00.epimg.net/iconos/v1.x/v1.0/promos/promo_og_los40.png", "ES", "Pop"),
    Station("SER Oriente",
        "https://playerservices.streamtheworld.com/api/livestream-redirect/SER_ASO_ORIENTE.mp3",
        "https://cadenaser00.epimg.net/favicon.png", "ES", "Talk"),
    Station("COPE Madrid", "https://flucast09-h-cloud.flumotion.com/cope/madrid.mp3",
        "https://www.cope.es/estaticos/apple-touch-icon-192x192.png", "ES", "Talk"),
)

class StationStore(context: Context, scope: CoroutineScope) {
    private val file = JsonFile(File(context.filesDir, "emisoras.json"), ListSerializer(Station.serializer()), scope)
    private val _stations = MutableStateFlow(file.read()?.takeIf { it.isNotEmpty() } ?: DEFAULT_STATIONS)
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    fun find(id: String): Station? = _stations.value.firstOrNull { it.id == id }
    fun findByUrl(url: String): Station? = _stations.value.firstOrNull { it.streamURL == url }
    fun contains(station: Station) = findByUrl(station.streamURL) != null

    private fun set(list: List<Station>) {
        _stations.value = list
        file.write(list)
    }

    fun add(station: Station): Boolean {
        if (contains(station)) return false
        set(_stations.value + station)
        return true
    }

    fun update(station: Station) = set(_stations.value.map { if (it.id == station.id) station else it })
    fun remove(id: String) = set(_stations.value.filterNot { it.id == id })

    fun move(from: Int, to: Int) {
        val list = _stations.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        set(list)
    }

    /**
     * Añade las de otra lista (la exportada de MacRadio o LinRadio) que no estén ya. La primera
     * importación, cuando aún no hay lista propia, sustituye a la de partida: si no, volverían
     * las que se quitaron en el Mac. Devuelve cuántas entraron, o null si no es una lista.
     */
    fun import(text: String): Int? {
        val incoming = try {
            (AppJson.parseToJsonElement(text) as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull { runCatching { AppJson.decodeFromJsonElement(Station.serializer(), it) }.getOrNull() }
                ?.filter { it.name.isNotBlank() && it.streamURL.isNotBlank() }
        } catch (e: Exception) {
            null
        } ?: return null
        if (incoming.isEmpty()) return null
        val base = if (file.exists()) _stations.value else emptyList()
        val fresh = incoming.filter { s -> base.none { it.streamURL == s.streamURL } }
            .distinctBy { it.streamURL }
        set(base + fresh)
        return fresh.size
    }

    fun export(): String = AppJson.encodeToString(ListSerializer(Station.serializer()), _stations.value)
}

// --- Historial ---------------------------------------------------------------------------------

private const val MAX_HISTORY = 1000     // las favoritas no cuentan: no caducan nunca

@Serializable
data class ListenedSong(
    val title: String,
    @SerialName("station_name") val stationName: String,
    val artist: String? = null,
    @SerialName("listened_at") val listenedAt: String =
        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("page_url") val pageUrl: String? = null,
    /** La guarda el usuario (corazón): sobrevive a «borrar para siempre» y a vaciar el resto. */
    val favorite: Boolean = false,
    val id: String = UUID.randomUUID().toString().uppercase(),
)

/** Un título que no se quiere en el historial: eslóganes y cuñas que algunas emisoras mandan. */
@Serializable
data class IgnoredTitle(
    @SerialName("station_name") val stationName: String,
    val title: String,
) {
    val key: String get() = ignoreKey(stationName, title)
}

private fun norm(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFKD)
    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()

fun ignoreKey(station: String, title: String) = "${norm(station)}\n${norm(title)}"

/** El mismo modelo que MacRadio: lo alimenta el reproductor, no la pantalla. */
class History(context: Context, scope: CoroutineScope) {
    private val songsFile = JsonFile(File(context.filesDir, "historial.json"), ListSerializer(ListenedSong.serializer()), scope)
    private val ignoredFile = JsonFile(File(context.filesDir, "titulos-ignorados.json"), ListSerializer(IgnoredTitle.serializer()), scope)

    private val _songs = MutableStateFlow(songsFile.read() ?: emptyList())
    private val _ignored = MutableStateFlow(ignoredFile.read() ?: emptyList())
    val songs: StateFlow<List<ListenedSong>> = _songs.asStateFlow()
    val ignored: StateFlow<List<IgnoredTitle>> = _ignored.asStateFlow()

    private fun setSongs(list: List<ListenedSong>) { _songs.value = list; songsFile.write(list) }
    private fun setIgnored(list: List<IgnoredTitle>) { _ignored.value = list; ignoredFile.write(list) }

    fun isIgnored(title: String, station: String): Boolean {
        val key = ignoreKey(station, title)
        return _ignored.value.any { it.key == key }
    }

    /**
     * Apunta una canción al empezar y devuelve su id, para rellenar luego la carátula y el orden
     * de título y artista. null si está ignorada; el id de la última si es la misma (una
     * reconexión la vuelve a mandar).
     */
    fun record(title: String, artist: String?, station: String): String? {
        val t = title.trim()
        if (t.isEmpty() || isIgnored(t, station)) return null
        _songs.value.firstOrNull()?.let { if (it.stationName == station && it.title == t) return it.id }
        val song = ListenedSong(title = t, artist = artist, stationName = station)
        setSongs(trim(listOf(song) + _songs.value))
        return song.id
    }

    fun update(id: String, change: (ListenedSong) -> ListenedSong) =
        setSongs(_songs.value.map { if (it.id == id) change(it) else it })

    fun toggleFavorite(id: String) = update(id) { it.copy(favorite = !it.favorite) }
    fun delete(id: String) = setSongs(_songs.value.filterNot { it.id == id })

    /** Vacía el historial pero deja las favoritas: perderlas sin querer dolería. */
    fun clearNonFavorites() = setSongs(_songs.value.filter { it.favorite })

    /** «Borrar para siempre»: no se vuelve a apuntar, y se van las copias salvo las favoritas. */
    fun ignoreAndPurge(song: ListenedSong) {
        val entry = IgnoredTitle(song.stationName, song.title)
        if (_ignored.value.none { it.key == entry.key }) setIgnored(listOf(entry) + _ignored.value)
        setSongs(_songs.value.filter { it.favorite || ignoreKey(it.stationName, it.title) != entry.key })
    }

    fun unignore(entry: IgnoredTitle) = setIgnored(_ignored.value.filterNot { it.key == entry.key })

    private fun trim(list: List<ListenedSong>): List<ListenedSong> {
        var excess = list.size - MAX_HISTORY
        if (excess <= 0) return list
        return list.asReversed().filter { s ->
            if (excess > 0 && !s.favorite) { excess--; false } else true
        }.asReversed()
    }
}

// --- Lo aprendido de cada emisora ---------------------------------------------------------------

/** Por URL del stream. Perderlo no es grave: se vuelve a aprender. */
@Serializable
data class StationLearned(
    @SerialName("title_lag") val titleLag: Double? = null,
    @SerialName("lyrics_offset") val lyricsOffset: Double? = null,
)

class StationMemory(context: Context, scope: CoroutineScope) {
    private val file = JsonFile(File(context.noBackupFilesDir, "emisoras-aprendido.json"),
        MapSerializer(String.serializer(), StationLearned.serializer()), scope)
    private val data = MutableStateFlow(file.read() ?: emptyMap())

    fun titleLag(url: String): Double = data.value[url]?.titleLag ?: 0.0
    fun lyricsOffset(url: String): Double = data.value[url]?.lyricsOffset ?: 0.0

    fun learnTitleLag(url: String, measured: Double): Boolean {
        val lag = es.altamirano.fmdroid.core.learnTitleLag(data.value[url]?.titleLag, measured) ?: return false
        change(url) { it.copy(titleLag = lag) }
        return true
    }

    fun setLyricsOffset(url: String, offset: Double) =
        change(url) { it.copy(lyricsOffset = es.altamirano.fmdroid.core.clampOffset(offset).takeIf { o -> o != 0.0 }) }

    private fun change(url: String, f: (StationLearned) -> StationLearned) {
        data.update { it + (url to f(it[url] ?: StationLearned())) }
        file.write(data.value)
    }
}
