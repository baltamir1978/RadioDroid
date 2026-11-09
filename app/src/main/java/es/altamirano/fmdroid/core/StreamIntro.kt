package es.altamirano.fmdroid.core

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * La cuña de entrada: un clip fijo (casi siempre anuncios) que algunos servidores ponen a cada
 * oyente que se conecta, antes del directo. La Indie manda unos 20 s, y en cada reconexión.
 *
 * Una cuña es un archivo: los mismos bytes en cada conexión, mientras que el directo nunca se
 * repite. Dos conexiones abiertas con unos segundos de diferencia coinciden lo que dura la cuña
 * y ni un byte más: así se mide ([introLength]). Ya conocida, se reconoce por sus primeros bytes
 * y se quita ([IcyReframer]). El servidor la manda deprisa (La Indie: 20 s de audio en 7 s), así
 * que saltarla cuesta unos segundos de conexión en vez de los anuncios.
 *
 * Traducción de `intro.py` de LinRadio y `StreamIntro.swift` de MacRadio.
 *
 * @param length bytes de audio que quitar; 0 apunta «esta emisora no tiene cuña».
 * @param prefix sus primeros bytes, para reconocerla antes de quitar nada.
 * @param checkedAt cuándo se midió, en segundos Unix.
 */
class StreamIntro(val length: Int, val prefix: ByteArray, val checkedAt: Double)

/** Por debajo, coincidir es sólo la cabecera de trama común. */
const val MINIMUM_INTRO = 32 * 1024
const val INTRO_PREFIX_LENGTH = 8 * 1024

/**
 * Dónde dejan de coincidir dos capturas del mismo stream, o 0 si no hay cuña.
 *
 * El directo justo tras la cuña empieza con los mismos bytes de cabecera que las tramas de la
 * cuña, así que la coincidencia en bruto se mete unos bytes en la primera trama del directo: el
 * corte vuelve a su palabra de sincronía (0xFF Ex de MP3).
 */
fun introLength(a: ByteArray, b: ByteArray): Int {
    val limit = min(a.size, b.size)
    var n = 0
    while (n < limit && a[n] == b[n]) n++
    // Coincidir hasta el final quiere decir que una captura se cortó, no que haya cuña.
    if (n < MINIMUM_INTRO || n >= limit) return 0
    for (i in n downTo maxOf(0, n - 8)) {
        if (i + 1 < a.size && a[i] == 0xFF.toByte() && (a[i + 1].toInt() and 0xE0) == 0xE0) return i
    }
    return n
}

/**
 * Las cuñas son anuncios y rotan: empiezan con la misma sintonía, pero cambia lo que duran. Con
 * cuña se mide cada hora; sin ella, cada tres días (las emisoras las añaden).
 */
fun introNeedsLearning(known: StreamIntro?, now: Double): Boolean {
    known ?: return true
    return now - known.checkedAt > if (known.length > 0) 3600.0 else 3 * 24 * 3600.0
}

/**
 * Quita una cuña conocida de un stream camino del reproductor.
 *
 * Los streams ICY intercalan un bloque de metadatos cada [metaint] bytes de audio, y el
 * reproductor cuenta esos bytes desde el principio de la conexión. Recortar audio del principio
 * dejaría todos los bloques fuera de sitio y se perderían los títulos, así que el stream se
 * desmonta y se vuelve a montar: se separan audio y metadatos al entrar, se tira el audio de la
 * cuña y el resto sale con su propio entramado al mismo intervalo, con el último título.
 *
 * No se tira nada hasta que los primeros bytes coinciden con la cuña recordada; si no coinciden,
 * todo pasa tal cual y [mismatch] avisa de que hay que volver a medirla.
 */
class IcyReframer(private val metaint: Int, private val intro: StreamIntro) {
    private var inAudioLeft = metaint
    private var inMetaLeft = -1
    private val inMeta = ByteArrayOutputStream()
    private var outAudioSinceMeta = 0
    private var pendingMeta: ByteArray? = null
    private var checking = true
    private val held = ByteArrayOutputStream()
    private var toSkip = intro.length

    var mismatch = false; private set
    var skipped = false; private set

    fun feed(data: ByteArray, count: Int = data.size): ByteArray {
        val out = ByteArrayOutputStream(count)
        var i = 0
        while (i < count) {
            if (metaint > 0 && inMetaLeft >= 0) {
                val n = min(inMetaLeft, count - i)
                inMeta.write(data, i, n)
                inMetaLeft -= n
                i += n
                if (inMetaLeft == 0) {
                    pendingMeta = inMeta.toByteArray()
                    inMetaLeft = -1
                    inAudioLeft = metaint
                }
            } else if (metaint > 0 && inAudioLeft == 0) {
                val blocks = data[i].toInt() and 0xFF
                i++
                if (blocks == 0) {
                    inAudioLeft = metaint
                } else {
                    inMeta.reset()
                    inMeta.write(blocks)
                    inMetaLeft = blocks * 16
                }
            } else {
                val n = if (metaint > 0) min(inAudioLeft, count - i) else count - i
                audio(data, i, n, out)
                i += n
                if (metaint > 0) inAudioLeft -= n
            }
        }
        return out.toByteArray()
    }

    private fun audio(chunk: ByteArray, from: Int, count: Int, out: ByteArrayOutputStream) {
        if (!checking) {
            drop(chunk, from, count, out)
            return
        }
        held.write(chunk, from, count)
        if (held.size() < intro.prefix.size) return
        checking = false
        val waiting = held.toByteArray()
        held.reset()
        if (intro.prefix.indices.all { waiting[it] == intro.prefix[it] }) {
            skipped = true
            drop(waiting, 0, waiting.size, out)
        } else {
            mismatch = true
            toSkip = 0
            emit(waiting, 0, waiting.size, out)
        }
    }

    private fun drop(chunk: ByteArray, from: Int, count: Int, out: ByteArrayOutputStream) {
        val n = min(toSkip, count)
        toSkip -= n
        if (n < count) emit(chunk, from + n, count - n, out)
    }

    private fun emit(audio: ByteArray, from: Int, count: Int, out: ByteArrayOutputStream) {
        if (metaint <= 0) {
            out.write(audio, from, count)
            return
        }
        var at = from
        var left = count
        while (left > 0) {
            val n = min(metaint - outAudioSinceMeta, left)
            out.write(audio, at, n)
            at += n
            left -= n
            outAudioSinceMeta += n
            if (outAudioSinceMeta == metaint) {
                val meta = pendingMeta
                if (meta != null) out.write(meta) else out.write(0)
                pendingMeta = null
                outAudioSinceMeta = 0
            }
        }
    }
}
