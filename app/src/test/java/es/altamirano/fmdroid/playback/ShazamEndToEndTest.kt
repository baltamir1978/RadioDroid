package es.altamirano.fmdroid.playback

import androidx.media3.common.C
import es.altamirano.fmdroid.core.ShazamSignature
import es.altamirano.fmdroid.net.Shazam
import es.altamirano.fmdroid.net.ShazamOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Toda la cadena con audio de verdad y el servidor de verdad (fuera del repositorio, porque
 * pregunta a Shazam): FM_SHAZAM_44K=<pcm s16 estéreo 44,1 kHz de una emisora> y
 * FM_SHAZAM_EXPECT=<título esperado>. Ver Tools/shazam-referencia/.
 */
class ShazamEndToEndTest {
    @Test fun tapSignatureAndServer() {
        val path = System.getenv("FM_SHAZAM_44K")
        assumeTrue(path != null)
        var clock = 0.0
        val tap = AudioTap { clock }
        tap.flush(44100, 2, C.ENCODING_PCM_16BIT)
        val bytes = File(path!!).readBytes()
        // Como en el móvil: trozos de ~23 ms y el reloj del stream avanzando con ellos.
        val chunk = 4096
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            clock += n / 4 / 44100.0
            tap.handleBuffer(ByteBuffer.wrap(bytes, i, n).slice())
            i += n
        }
        assertEquals(14.0, tap.seconds, 0.01)                     // guarda los últimos 14 s
        val (pcm, startAt) = tap.snapshot(12.0)!!
        assertEquals(clock - 12.0, startAt, 0.01)
        val outcome = runBlocking { Shazam.recognize(ShazamSignature.of(pcm)) }
        println("Shazam: $outcome  (el trozo empieza en el segundo ${"%.1f".format(startAt)} de la grabación)")
        assertTrue(outcome is ShazamOutcome.Match)
        System.getenv("FM_SHAZAM_EXPECT")?.let { assertEquals(it, (outcome as ShazamOutcome.Match).title) }
    }
}
