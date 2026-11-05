package es.altamirano.fmdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * La huella tiene que salir igual que la de la referencia en Python (shazamio 0.4 con la banda
 * de 3,5–5,5 kHz corregida). `synthetic.pcm` y `synthetic.sig` se generaron con
 * `Tools/shazam-referencia/` a partir de audio sintético, para no meter música en el repositorio.
 */
class ShazamSignatureTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/shazam/$name")!!.readBytes()

    private fun pcm(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(b.remaining()).also { b.get(it) }
    }

    @Test fun matchesPythonReferenceExactly() {
        val sig = ShazamSignature.of(pcm(resource("synthetic.pcm")))
        assertArrayEquals(resource("synthetic.sig"), sig.encode())
    }

    @Test fun headerAndUri() {
        val sig = ShazamSignature.of(pcm(resource("synthetic.pcm")))
        assertEquals(128000, sig.numberSamples)
        assertEquals(8000, sig.sampleMs)
        assertTrue(sig.toUri().startsWith("data:audio/vnd.shazam.sig;base64,"))
        val bytes = Base64.getDecoder().decode(sig.toUri().substringAfter(","))
        val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), head.getInt(0))
        assertEquals(bytes.size - 48, head.getInt(8))
        // Las cuatro bandas tienen picos (shazamio dejaba vacía la de 3,5–5,5 kHz).
        assertEquals(setOf(0, 1, 2, 3), sig.peakCounts.keys)
    }

    /** Con una grabación de radio de verdad, fuera del repositorio: FM_SHAZAM_PCM=…pcm (y su .sig). */
    @Test fun matchesReferenceOnRealRadio() {
        val path = System.getenv("FM_SHAZAM_PCM")
        assumeTrue(path != null)
        val sig = ShazamSignature.of(pcm(File(path!!).readBytes()))
        assertArrayEquals(File(path.removeSuffix(".pcm") + ".sig").readBytes(), sig.encode())
    }
}
