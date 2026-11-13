package es.altamirano.fmdroid.core

import java.text.Normalizer

private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Qué emisora se pide de viva voz («pon Radio 3» en Android Auto o al asistente): la posición en
 * [names] de la que mejor casa con [query], sin tildes ni mayúsculas, o null si ninguna.
 * Gana el nombre igual; después el que contiene lo pedido o está contenido en ello (el más
 * parecido en longitud); después el que comparte más palabras.
 */
fun matchStation(query: String?, names: List<String>): Int? {
    val q = plain(query ?: return null)
    if (q.isEmpty()) return null
    val plainNames = names.map(::plain)
    plainNames.indexOf(q).takeIf { it >= 0 }?.let { return it }
    val compact = q.replace(" ", "")
    plainNames.withIndex()
        .filter { (_, n) -> n.isNotEmpty() && n.replace(" ", "").let { it.contains(compact) || compact.contains(it) } }
        .minByOrNull { (_, n) -> kotlin.math.abs(n.length - q.length) }?.let { return it.index }
    val words = q.split(' ').toSet()
    return plainNames.withIndex().map { (i, n) -> i to n.split(' ').count { it in words } }
        .filter { it.second > 0 }.maxByOrNull { it.second }?.first
}
