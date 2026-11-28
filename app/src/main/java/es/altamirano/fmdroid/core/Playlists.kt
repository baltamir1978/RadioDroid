package es.altamirano.fmdroid.core

/**
 * Muchas webs de emisoras no dan la dirección del sonido sino una lista `.pls` o `.m3u`: un
 * ficherito de texto con la dirección dentro. ExoPlayer no las abre (sólo entiende `.m3u8`, que es
 * HLS y otra cosa), así que al guardar una emisora propia se lee la lista y se guarda lo de dentro.
 */
object Playlists {
    /** Por cómo termina la ruta, sin la consulta. `.m3u8` no: es HLS y ExoPlayer ya lo reproduce. */
    fun isPlaylistUrl(url: String): Boolean {
        val path = url.trim().lowercase().substringBefore('#').substringBefore('?')
        return path.endsWith(".pls") || path.endsWith(".m3u")
    }

    /**
     * Las direcciones de la lista, en su orden. En un PLS son las `FileN=` (ordenadas por N, que no
     * siempre vienen en orden); en un M3U, las líneas que no son comentario. Sólo http(s): una ruta
     * relativa o un fichero local no sirven para una emisora.
     */
    fun entries(text: String): List<String> {
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val isPls = lines.firstOrNull()?.equals("[playlist]", ignoreCase = true) == true ||
            lines.any { FILE_ENTRY.matches(it) }
        val urls = if (isPls) {
            lines.mapNotNull { FILE_ENTRY.matchEntire(it) }
                .sortedBy { it.groupValues[1].toInt() }
                .map { it.groupValues[2].trim() }
        } else {
            lines.filterNot { it.startsWith("#") }
        }
        return urls.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    private val FILE_ENTRY = Regex("""(?i)file(\d+)\s*=\s*(.+)""")
}
