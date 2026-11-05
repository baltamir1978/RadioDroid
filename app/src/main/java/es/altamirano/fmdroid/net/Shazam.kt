package es.altamirano.fmdroid.net

import es.altamirano.fmdroid.core.ShazamSignature
import es.altamirano.fmdroid.core.num
import es.altamirano.fmdroid.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

sealed interface ShazamOutcome {
    /**
     * [offset]: el segundo de la canción en que empieza el trozo enviado (medido: el mismo
     * trozo con 8 o 12 s da lo mismo, y empezando 5 s después da 5 s más).
     */
    data class Match(
        val title: String,
        val artist: String?,
        val offset: Double,
        val coverArt: String?,
        val pageUrl: String?,
    ) : ShazamOutcome
    data object NoMatch : ShazamOutcome
    data object Failed : ShazamOutcome
}

/**
 * Shazam sin ShazamKit: la misma petición que hace su app, con la huella calculada aquí
 * (`ShazamSignature`). No es una API pública: si Shazam la cambia, deja de funcionar y la app
 * sigue sin reconocimiento, como antes (ver `CLAUDE.md`).
 */
object Shazam {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun recognize(signature: ShazamSignature): ShazamOutcome = withContext(Dispatchers.IO) {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val country = Locale.getDefault().country.ifEmpty { "ES" }
        val url = "https://amp.shazam.com/discovery/v5/$lang/$country/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true" +
            "&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val body = buildJsonObject {
            put("timezone", TimeZone.getDefault().id)
            putJsonObject("signature") {
                put("uri", signature.toUri())
                put("samplems", signature.sampleMs)
            }
            put("timestamp", System.currentTimeMillis())
            putJsonObject("context") {}
            putJsonObject("geolocation") {}
        }.toString()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", lang)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext ShazamOutcome.Failed
                parse(r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            ShazamOutcome.Failed
        }
    }

    fun parse(text: String): ShazamOutcome {
        val data = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return ShazamOutcome.Failed
        val match = (data["matches"] as? JsonArray)?.firstOrNull() as? JsonObject
        val track = data["track"] as? JsonObject
        if (match == null || track == null) return ShazamOutcome.NoMatch
        val title = track.str("title") ?: return ShazamOutcome.NoMatch
        return ShazamOutcome.Match(
            title = title,
            artist = track.str("subtitle"),
            offset = match.num("offset") ?: 0.0,
            coverArt = (track["images"] as? JsonObject)?.str("coverart"),
            pageUrl = track.str("url"),
        )
    }
}
