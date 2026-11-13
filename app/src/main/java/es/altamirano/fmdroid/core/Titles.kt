package es.altamirano.fmdroid.core

import java.text.Normalizer

/**
 * El título que manda la emisora (ICY) y el reloj de la letra.
 *
 * Traducción de `titles.py` de LinRadio (que a su vez viene de `RadioPlayer.swift` de MacRadio):
 * partir «Artista - Título», reconocer los eslóganes y saber por dónde va la canción.
 */
data class StreamTitle(val track: String, val artist: String?) {
    val key: String get() = "${artist.orEmpty()}|$track".lowercase()

    /** Para las emisoras que mandan «Título - Artista». */
    fun swapped(): StreamTitle = if (artist == null) this else StreamTitle(artist, track)
}

object Titles {
    /** Los 40 Classic manda a veces un código numérico de rotación en vez de una canción. */
    fun isMeaningful(s: String): Boolean = s.any { it.isLetter() }

    /** Sin tildes, sin mayúsculas, sólo letras y números: para comparar nombres. */
    fun compact(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
            .filter { it.isLetterOrDigit() }

    private val separator = Regex(
        """\s*(?:,|&|\+|/|\b(?:y|and|con|with|x|vs|feat|ft|featuring)\b\.?)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** «A y B», «A & B», «A feat. B», «A, B», «A x B» → [A, B]. */
    fun artistNames(artist: String): List<String> =
        separator.split(artist).map { it.trim() }.filter { it.isNotEmpty() }

    /** Como [compact], ciego a cómo se escribe el «y»: «Fito y Fitipaldis» = «Fito & Fitipaldis». */
    fun artistKey(s: String): String = artistNames(s).joinToString("") { compact(it) }

    /** Cadena 100 manda «La Mejor Variedad Musical - CADENA 100» entre canciones. */
    fun announcesStation(title: StreamTitle, station: String): Boolean {
        val key = compact(station)
        return key.isNotEmpty() && listOfNotNull(title.track, title.artist).any { compact(it) == key }
    }

    private val entity = Regex("""&(#\d{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|quot|apos|lt|gt|nbsp);""")

    /** Algunas emisoras mandan el título como HTML: «Simon &amp; Garfunkel», «Don&#39;t». */
    fun decodeEntities(s: String): String = if ('&' !in s) s else entity.replace(s) { m ->
        val name = m.groupValues[1]
        when {
            name == "amp" -> "&"
            name == "quot" -> "\""
            name == "apos" -> "'"
            name == "lt" -> "<"
            name == "gt" -> ">"
            name == "nbsp" -> " "
            else -> {
                val code = if (name[1] == 'x' || name[1] == 'X') name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code) && code >= 32) String(Character.toChars(code)) else m.value
            }
        }
    }

    /** El título ICY como canción, o null si no lo es (un código, el nombre de la emisora). */
    fun parse(raw: String, station: String = ""): StreamTitle? {
        val cleaned = decodeEntities(raw).trim()
        if (!isMeaningful(cleaned)) return null
        // Primero « - »: un «-» a secas partiría en dos nombres como «M-Clan».
        val sep = if (" - " in cleaned) " - " else "-"
        val parts = cleaned.split(sep)
        val title = if (parts.size >= 2) {
            val artist = parts[0].trim()
            val track = parts.drop(1).joinToString(sep).trim()
            if (artist.isNotEmpty() && track.isNotEmpty()) StreamTitle(track, artist)
            else StreamTitle(cleaned, null)
        } else {
            StreamTitle(cleaned, null)
        }
        return if (announcesStation(title, station)) null else title
    }

    /** Laxa: «Miedo (Directo)» de la emisora es el «Miedo» de Shazam. */
    fun sameSong(a: String, b: String): Boolean {
        val x = compact(a)
        val y = compact(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x in y || y in x)
    }

    private fun sameTrack(a: String, b: String?): Boolean =
        b != null && sameSong(Lrc.stripDecorations(a).ifBlank { a }, Lrc.stripDecorations(b).ifBlank { b })

    private fun sharesArtist(ours: String, theirs: String?): Boolean {
        val by = compact(theirs.orEmpty())
        return by.isNotEmpty() && artistNames(ours).map(::compact).any { it.isNotEmpty() && (it in by || by in it) }
    }

    /**
     * ¿Lo que devuelve el buscador de carátulas ([foundTrack], [foundArtist]) es lo que dijo la
     * emisora? El buscador siempre contesta algo: con «Estopa & Rozalén - Vino tinto» daba un
     * disco de homenaje a Aute, y con «Morat & Duki - 506», otra canción de los dos. El título
     * tiene que casar (también al revés, por las emisoras que mandan «Título - Artista») y, si
     * [needArtist] (búsquedas en las que no iban todos los artistas), alguno de ellos también.
     */
    fun matchesArtwork(title: StreamTitle, foundTrack: String?, foundArtist: String?, needArtist: Boolean): Boolean {
        val artist = title.artist ?: return sameTrack(title.track, foundTrack)
        return (sameTrack(title.track, foundTrack) && (!needArtist || sharesArtist(artist, foundArtist))) ||
            (sameTrack(artist, foundTrack) && (!needArtist || sharesArtist(title.track, foundArtist)))
    }

    /**
     * Qué buscar para la carátula, por orden, y si hay que exigir el artista en lo que salga:
     * todo junto; el artista principal de una colaboración («Estopa & Rozalén» → «Estopa»), que
     * es como suele estar la canción en la tienda; y el título solo.
     */
    fun artworkSearches(title: StreamTitle): List<Pair<String, Boolean>> {
        val artist = title.artist ?: return listOf(title.track to false)
        val names = artistNames(artist)
        return buildList {
            add("$artist ${title.track}" to false)
            if (names.size > 1) add("${names[0]} ${title.track}" to true)
            add(title.track to true)
        }
    }

    /**
     * ¿La emisora manda «Título - Artista»? (Cassette FM lo hace.) Nada en el título lo dice,
     * pero iTunes sí: si su artista es lo que tomamos por la canción, iba al revés.
     */
    fun isSwapped(title: StreamTitle, foundArtist: String?): Boolean {
        if (title.artist == null || foundArtist == null) return false
        val itunes = artistKey(foundArtist)
        if (itunes.isEmpty()) return false
        val asTrack = artistKey(title.track)
        val asArtist = artistKey(title.artist)
        return asTrack == itunes || (itunes in asTrack && itunes !in asArtist)
    }
}

// --- El reloj de la letra ---------------------------------------------------------------------

const val LYRICS_LEAD = 1.0          // las líneas se encienden un poco antes de cantarse
const val MAX_OFFSET = 15.0
const val STALE_GRACE = 20.0         // pasada la duración + esto, se pregunta a Shazam
const val ASSUMED_SONG_LENGTH = 300.0
private val PLAUSIBLE_LAG = -5.0..30.0

/**
 * Cuánto tarda la emisora en cambiar el título, promediado con lo que se sabía. Fuera de lo
 * verosímil (Shazam acertando la cola de la canción anterior) no se aprende nada.
 */
fun learnTitleLag(known: Double?, measured: Double): Double? {
    if (measured !in PLAUSIBLE_LAG) return null
    return if (known == null) measured else (known + measured) / 2
}

fun clampOffset(offset: Double): Double = offset.coerceIn(-MAX_OFFSET, MAX_OFFSET)

/**
 * Cuándo empezó la canción, en «tiempo del stream» (segundos de audio oído, ver
 * `NowPlaying.streamNow`), y si se puede confiar en ello: se vio *cambiar* el título mientras
 * se escuchaba, o Shazam dijo por dónde va.
 *
 * [offset] es el ajuste a mano de la emisora (− / + / tocar una línea). No se guarda entre
 * sesiones un «retraso de conexión»: cambia con la cuña y el búfer y desfasaba la letra.
 */
data class SongClock(
    val startedAt: Double? = null,
    val exact: Boolean = false,
    val offset: Double = 0.0,
) {
    /** El primer título tras sintonizar es de una canción ya empezada: no es exacto. */
    fun titleChanged(heardAt: Double, titleLag: Double, seenTitleBefore: Boolean) =
        copy(startedAt = heardAt - titleLag, exact = seenTitleBefore)

    fun shazamMatched(matchedAt: Double, songOffset: Double) =
        copy(startedAt = matchedAt - songOffset, exact = true)

    fun lyricsStart(): Double? = startedAt?.let { it - (LYRICS_LEAD + offset) }

    fun elapsed(now: Double): Double? = lyricsStart()?.let { now - it }

    fun nudge(seconds: Double) = copy(offset = clampOffset(offset + seconds))

    /**
     * Se ha tocado la línea que suena. Con el principio conocido, la corrección es de la
     * emisora y va al ajuste; si se sintonizó a mitad, es el principio que faltaba.
     */
    fun syncToLine(lineTime: Double, tappedAt: Double, reaction: Double = 0.3): SongClock {
        val wanted = tappedAt - lineTime - reaction
        return if (exact && startedAt != null) {
            copy(offset = clampOffset(startedAt - wanted - LYRICS_LEAD))
        } else {
            copy(startedAt = wanted + LYRICS_LEAD + offset, exact = true)
        }
    }

    /** Cuándo sospechar que la emisora se ha quedado con el título viejo. */
    fun staleDeadline(duration: Double?): Double? =
        startedAt?.let { it + (duration ?: ASSUMED_SONG_LENGTH) + STALE_GRACE }
}
