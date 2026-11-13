package es.altamirano.fmdroid.net

import es.altamirano.fmdroid.core.Lrc
import es.altamirano.fmdroid.core.SongLyrics
import es.altamirano.fmdroid.core.StreamTitle
import es.altamirano.fmdroid.core.Titles
import es.altamirano.fmdroid.core.num
import es.altamirano.fmdroid.core.str
import es.altamirano.fmdroid.data.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

const val USER_AGENT = "FMDroid/0.1 (+https://github.com/baltamir1978/RadioDroid)"

/** La red o el servidor fallaron: merece la pena volver a intentarlo (a diferencia de «no hay»). */
class NetworkException(message: String) : IOException(message)

enum class Lookup { FOUND, NOT_FOUND, FAILED }

object Http {
    /** 20 s y no 12 como en MacRadio: LRCLIB llega a tardar 14 s en contestar (LinRadio). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Si el servidor dice «ocupado» (503/429) se reintenta a los 2 y a los 4 s. */
    suspend fun getJson(base: String, params: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val url = base.toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            var attempt = 0
            while (true) {
                val result = try {
                    client.newCall(request).execute().use { r ->
                        if (r.isSuccessful) Result.success(r.body?.string().orEmpty())
                        else Result.failure(HttpStatus(r.code))
                    }
                } catch (e: IOException) {
                    throw NetworkException(e.message ?: "sin red")
                }
                val err = result.exceptionOrNull()
                if (err is HttpStatus) {
                    if ((err.code == 429 || err.code == 503) && attempt < 2) {
                        attempt++
                        delay(2000L * attempt)
                        continue
                    }
                    throw NetworkException("el servidor respondió ${err.code}")
                }
                return@withContext try {
                    json.parseToJsonElement(result.getOrThrow())
                } catch (e: Exception) {
                    throw NetworkException("la respuesta no es JSON")
                }
            }
            @Suppress("UNREACHABLE_CODE") error("inalcanzable")
        }

    private class HttpStatus(val code: Int) : Exception()
}

// --- LRCLIB ------------------------------------------------------------------------------------

sealed interface LyricsOutcome {
    data class Found(val lyrics: SongLyrics) : LyricsOutcome
    data object NotFound : LyricsOutcome
    data object Failed : LyricsOutcome
}

object Lrclib {
    private const val API = "https://lrclib.net/api/search"

    /**
     * La canción tal cual; si no aparece, con título y artista cambiados (algunas emisoras van
     * al revés); luego por el artista principal de una colaboración y, al final, por el título
     * solo aceptándolo si es de alguno de los artistas («El Canto del Loco y Amaia Montero»).
     */
    suspend fun lookup(track: String, artist: String?): LyricsOutcome {
        val first = search(track, artist)
        if (first != LyricsOutcome.NotFound || artist == null) return first
        val swapped = search(artist, track)
        if (swapped != LyricsOutcome.NotFound) return swapped
        val names = Titles.artistNames(artist)
        if (names.size > 1) {
            val main = search(track, names[0], names)
            if (main != LyricsOutcome.NotFound) return main
        }
        return search(track, null, names)
    }

    private suspend fun search(track: String, artist: String?, byAnyOf: List<String> = emptyList()): LyricsOutcome {
        val title = Lrc.stripDecorations(track).trim().ifEmpty { track }
        val params = buildMap {
            put("track_name", title)
            if (artist != null) put("artist_name", artist)
        }
        val hits = try {
            Http.getJson(API, params) as? JsonArray ?: return LyricsOutcome.Failed
        } catch (e: NetworkException) {
            return LyricsOutcome.Failed
        }
        val best = Lrc.pick(Lrc.hitsOf(hits), track, byAnyOf) ?: return LyricsOutcome.NotFound
        return Lrc.fromHit(best)?.let { LyricsOutcome.Found(it) } ?: LyricsOutcome.NotFound
    }
}

// --- Radio Browser ---------------------------------------------------------------------------

object RadioBrowser {
    /** Si un servidor espejo no contesta, el siguiente. */
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info/json",
        "https://fi1.api.radio-browser.info/json",
        "https://de2.api.radio-browser.info/json",
    )

    suspend fun search(name: String, country: String? = null, limit: Int = 40): List<Station> =
        query("stations/search", buildMap {
            put("name", name); put("limit", "$limit"); put("hidebroken", "true")
            put("order", "votes"); put("reverse", "true")
            if (country != null) put("countrycode", country)
        })

    suspend fun top(country: String?, limit: Int = 50): List<Station> =
        query("stations/topvote", buildMap {
            put("limit", "$limit"); put("hidebroken", "true")
            if (country != null) put("countrycode", country)
        })

    private suspend fun query(path: String, params: Map<String, String>): List<Station> {
        var last: NetworkException? = null
        for (mirror in mirrors) {
            try {
                val data = Http.getJson("$mirror/$path", params) as? JsonArray ?: continue
                return data.filterIsInstance<JsonObject>().mapNotNull(::toStation)
            } catch (e: NetworkException) {
                last = e
            }
        }
        throw last ?: NetworkException("Radio Browser no contesta")
    }

    private fun toStation(raw: JsonObject): Station? {
        val url = raw.str("url_resolved")?.takeIf { it.isNotBlank() } ?: return null
        val name = raw.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tags = raw.str("tags").orEmpty()
        return Station(
            name = name,
            streamURL = url,
            logoURL = raw.str("favicon")?.takeIf { it.isNotBlank() },
            country = raw.str("countrycode")?.takeIf { it.isNotBlank() },
            genre = tags.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            stationuuid = raw.str("stationuuid"),
        )
    }
}

// --- Carátulas: iTunes Search -----------------------------------------------------------------

data class Artwork(
    val imageUrl: String?,       // 600×600; null: no se dio con la canción, sólo con su artista
    val pageUrl: String?,
    val track: String?,          // cómo la llama iTunes: dice si la emisora va al revés
    val artist: String?,
    val duration: Double? = null, // s
)

object ITunes {
    /**
     * La canción en la tienda, probando las búsquedas de [Titles.artworkSearches] hasta que una
     * dé con ella de verdad ([Titles.matchesArtwork]): quedarse con lo primero que saliera dejaba
     * sin carátula, o con la de otra canción, muchas colaboraciones («A & B»).
     * iTunes corta las ráfagas con un 403: eso es «falló», no «no hay».
     */
    suspend fun artwork(track: String, artist: String?): Pair<Lookup, Artwork?> {
        val title = StreamTitle(track, artist)
        if (track.isBlank()) return Lookup.NOT_FOUND to null
        var swappedBy: String? = null
        for ((term, needArtist) in Titles.artworkSearches(title)) {
            val hits = search(term) ?: return Lookup.FAILED to null
            // Aunque no esté la canción, el artista de lo que sale delata a la emisora que
            // manda «Título - Artista».
            if (swappedBy == null) swappedBy = hits.firstOrNull()?.str("artistName")?.takeIf { Titles.isSwapped(title, it) }
            val hit = hits.firstOrNull {
                it.str("artworkUrl100") != null &&
                    Titles.matchesArtwork(title, it.str("trackName"), it.str("artistName"), needArtist)
            } ?: continue
            return Lookup.FOUND to Artwork(
                imageUrl = hit.str("artworkUrl100")!!.replace("100x100bb", "600x600bb"),
                pageUrl = hit.str("trackViewUrl"),
                track = hit.str("trackName"),
                artist = hit.str("artistName"),
                duration = hit.num("trackTimeMillis")?.let { it / 1000.0 },
            )
        }
        return Lookup.NOT_FOUND to swappedBy?.let { Artwork(null, null, null, it) }
    }

    /** Las primeras canciones que casan con [term], o null si la red o el servidor fallaron. */
    private suspend fun search(term: String): List<JsonObject>? {
        val data = try {
            Http.getJson("https://itunes.apple.com/search",
                mapOf("term" to term, "entity" to "song", "limit" to "10")) as? JsonObject
        } catch (e: NetworkException) {
            return null
        } ?: return null
        return (data["results"] as? JsonArray)?.filterIsInstance<JsonObject>()
    }
}
