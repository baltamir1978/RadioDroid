package es.altamirano.fmdroid.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

data class LyricLine(val time: Double, val text: String)

data class SongLyrics(
    val synced: List<LyricLine> = emptyList(),   // vacía si sólo hay texto
    val plain: List<String> = emptyList(),
    val instrumental: Boolean = false,
    val duration: Double? = null,
) {
    val isEmpty: Boolean get() = synced.isEmpty() && plain.isEmpty()
    val lines: List<String> get() = if (synced.isNotEmpty()) synced.map { it.text } else plain

    /** La línea que se canta a [elapsed] segundos, o null antes de la primera. */
    fun lineIndex(elapsed: Double): Int? {
        var found: Int? = null
        for ((i, line) in synced.withIndex()) {
            if (line.time <= elapsed) found = i else break
        }
        return found
    }
}

/**
 * Lo que no depende de la red en `lyrics.py` de LinRadio: leer el LRC y elegir, de lo que
 * devuelve LRCLIB, la letra de la canción buena.
 */
object Lrc {
    private val stamp = Regex("""^\[([^\]]*)]""")
    private val offsetTag = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * Líneas `[mm:ss.xx] texto`. Una línea con varias marcas (un estribillo escrito una vez)
     * da una entrada por marca. `[offset:+500]` adelanta toda la letra medio segundo.
     */
    fun parse(text: String): List<LyricLine> {
        var offset = 0.0
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            offsetTag.find(raw)?.let { offset = it.groupValues[1].toDouble() / 1000.0 }
            var rest = raw
            val stamps = mutableListOf<Double>()
            while (true) {
                val m = stamp.find(rest) ?: break
                parseStamp(m.groupValues[1])?.let { stamps += it }
                rest = rest.substring(m.range.last + 1)
            }
            val body = rest.trim()
            stamps.forEach { lines += LyricLine(it, body) }
        }
        return lines.map { it.copy(time = maxOf(0.0, it.time - offset)) }.sortedBy { it.time }
    }

    private fun parseStamp(tag: String): Double? {
        val parts = tag.split(":")
        if (parts.size != 2) return null
        val min = parts[0].toDoubleOrNull() ?: return null
        val sec = parts[1].toDoubleOrNull() ?: return null
        return min * 60 + sec
    }

    /** Fuera «(Radio Edit)», «[Remastered]» y las coletillas «feat.» que la base no tiene. */
    fun stripDecorations(s: String): String {
        var out = s
        for (opener in "([") {
            val i = out.indexOf(opener)
            if (i > 0) out = out.substring(0, i)
        }
        for (marker in listOf(" feat.", " ft.", " featuring ")) {
            val i = out.lowercase().indexOf(marker)
            if (i >= 0) out = out.substring(0, i)
        }
        return out
    }

    /**
     * Prefiere una con letra sincronizada cuyo título case de verdad. Los títulos de la radio
     * son descuidados, así que la búsqueda es laxa y es este filtro lo que evita la letra de
     * otra canción. Las muy cortas son subidas basura.
     */
    fun pick(hits: List<JsonObject>, track: String, byAnyOf: List<String>): JsonObject? {
        val wanted = Titles.compact(stripDecorations(track))
        val artists = byAnyOf.map { Titles.compact(it) }.filter { it.isNotEmpty() }

        fun plausible(hit: JsonObject): Boolean {
            if ((hit.num("duration") ?: 120.0) <= 45) return false
            if (artists.isNotEmpty()) {
                val by = Titles.compact(hit.str("artistName").orEmpty())
                if (by.isEmpty() || artists.none { it in by || by in it }) return false
            }
            val title = Titles.compact(stripDecorations(hit.str("trackName").orEmpty()))
            return title.isNotEmpty() && (wanted in title || title in wanted)
        }

        val candidates = hits.filter(::plausible)
        return candidates.firstOrNull { !it.str("syncedLyrics").isNullOrBlank() }
            ?: candidates.firstOrNull {
                !it.str("plainLyrics").isNullOrBlank() || it.bool("instrumental") == true
            }
    }

    fun fromHit(hit: JsonObject): SongLyrics? {
        val duration = hit.num("duration")
        if (hit.bool("instrumental") == true) return SongLyrics(instrumental = true, duration = duration)
        val synced = parse(hit.str("syncedLyrics").orEmpty())
        val plain = hit.str("plainLyrics").orEmpty().lines().map { it.trim() }
            .dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        val lyrics = SongLyrics(synced = synced, plain = plain, duration = duration)
        return if (lyrics.isEmpty) null else lyrics
    }

    fun hitsOf(json: JsonArray): List<JsonObject> = json.filterIsInstance<JsonObject>()
}

// Lectura tolerante de JSON: un campo con otro tipo cuenta como ausente.
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
