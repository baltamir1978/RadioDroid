package es.altamirano.fmdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class StreamIntroTest {
    /** Bytes que parecen tramas MP3: una palabra de sincronía cada 417 bytes. */
    private fun mp3ish(seed: Int, size: Int): ByteArray {
        val rnd = Random(seed)
        return ByteArray(size) { i ->
            when (i % 417) {
                0 -> 0xFF.toByte()
                1 -> 0xFB.toByte()
                else -> rnd.nextInt().toByte()
            }
        }
    }

    /** Un stream ICY: un bloque de metadatos cada [metaint] bytes de audio. */
    private fun icy(audio: ByteArray, metaint: Int, titles: Map<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        var block = 0
        var i = 0
        while (i < audio.size) {
            val n = minOf(metaint, audio.size - i)
            out.write(audio, i, n)
            i += n
            if (n == metaint) {
                val title = titles[block]?.toByteArray()
                if (title != null) {
                    val blocks = (title.size + 15) / 16
                    out.write(blocks)
                    out.write(title.copyOf(blocks * 16))
                } else {
                    out.write(0)
                }
                block++
            }
        }
        return out.toByteArray()
    }

    /** Separa lo que saldría hacia el reproductor en audio y títulos, a intervalos de [metaint]. */
    private fun parse(stream: ByteArray, metaint: Int): Pair<ByteArray, List<String>> {
        val audio = ByteArrayOutputStream()
        val titles = mutableListOf<String>()
        var i = 0
        while (i < stream.size) {
            val n = minOf(metaint, stream.size - i)
            audio.write(stream, i, n)
            i += n
            if (i < stream.size) {
                val size = (stream[i++].toInt() and 0xFF) * 16
                if (size > 0) {
                    titles += String(stream, i, size).trimEnd('\u0000')
                    i += size
                }
            }
        }
        return audio.toByteArray() to titles
    }

    private fun known(intro: ByteArray) = StreamIntro(intro.size, intro.copyOf(INTRO_PREFIX_LENGTH), 0.0)

    @Test fun mideLaCuna() {
        val intro = mp3ish(1, 100_000)
        val a = intro + mp3ish(2, 50_000)
        val n = introLength(a, intro + mp3ish(3, 50_000))
        assertTrue(kotlin.math.abs(n - intro.size) <= 8)
        assertEquals(0xFF.toByte(), a[n])
    }

    @Test fun sinCuna() {
        assertEquals(0, introLength(mp3ish(4, 100_000), mp3ish(5, 100_000)))
        val same = mp3ish(6, 100_000)
        assertEquals("una captura cortada no es una cuña", 0, introLength(same, same))
        val short = mp3ish(7, MINIMUM_INTRO - 1000)
        assertEquals("la cabecera común no es una cuña", 0, introLength(short + mp3ish(8, 9000), short + mp3ish(9, 9000)))
    }

    @Test fun quitaLaCunaYConservaLosTitulos() {
        val intro = mp3ish(10, 40_000)
        val live = mp3ish(11, 64_000)
        val stream = icy(intro + live, 16_000, mapOf(1 to "StreamTitle='Anuncio';", 3 to "StreamTitle='Cancion';"))
        val r = IcyReframer(16_000, known(intro))
        val (audio, titles) = parse(r.feed(stream), 16_000)
        assertTrue(r.skipped)
        assertFalse(r.mismatch)
        assertArrayEquals(live.copyOf(audio.size), audio)
        assertTrue(audio.size >= 48_000)
        assertEquals("StreamTitle='Cancion';", titles.last())
    }

    @Test fun daIgualComoLleguenLosTrozos() {
        val intro = mp3ish(12, 40_000)
        val stream = icy(intro + mp3ish(13, 64_000), 8_192, mapOf(2 to "StreamTitle='A';", 7 to "StreamTitle='B';"))
        val whole = IcyReframer(8_192, known(intro)).feed(stream)
        val r = IcyReframer(8_192, known(intro))
        val pieces = ByteArrayOutputStream()
        var i = 0
        var size = 1
        while (i < stream.size) {
            val n = minOf(size, stream.size - i)
            pieces.write(r.feed(stream.copyOfRange(i, i + n)))
            i += n
            size = size % 997 + 13
        }
        assertArrayEquals(whole, pieces.toByteArray())
    }

    @Test fun otraCunaPasaTalCualYAvisa() {
        val stream = mp3ish(14, 60_000)
        val r = IcyReframer(0, known(mp3ish(15, 40_000)))
        assertArrayEquals(stream, r.feed(stream))
        assertTrue(r.mismatch)
        assertFalse(r.skipped)
    }

    @Test fun sinMetadatosSoloRecorta() {
        val intro = mp3ish(16, 40_000)
        val live = mp3ish(17, 20_000)
        assertArrayEquals(live, IcyReframer(0, known(intro)).feed(intro + live))
    }

    @Test fun cuandoVolverAMedir() {
        assertTrue(introNeedsLearning(null, 1000.0, foreground = true))
        val with = StreamIntro(1, ByteArray(1), 1000.0)
        assertFalse(introNeedsLearning(with, 1000.0 + 3599, foreground = true))
        assertTrue(introNeedsLearning(with, 1000.0 + 3601, foreground = true))
        val without = StreamIntro(0, ByteArray(0), 1000.0)
        assertFalse(introNeedsLearning(without, 1000.0 + 2 * 86400, foreground = true))
        assertTrue(introNeedsLearning(without, 1000.0 + 4 * 86400, foreground = true))
    }

    /** Puesta siempre desde un widget o el coche, también tiene que librarse de la cuña. */
    @Test fun laPrimeraMedidaNoEsperaALaApp() {
        assertTrue(introNeedsLearning(null, 1000.0, foreground = false))
        assertTrue("una medida que ya no vale", introNeedsLearning(StreamIntro(1, ByteArray(1), 0.0), 1000.0, foreground = false))
        assertFalse("lo periódico, sólo a la vista", introNeedsLearning(StreamIntro(1, ByteArray(1), 1000.0), 1000.0 + 7200, foreground = false))
    }
}
