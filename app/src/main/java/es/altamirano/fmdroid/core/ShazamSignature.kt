package es.altamirano.fmdroid.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * La huella de audio que entiende Shazam, calculada en el móvil.
 *
 * Traducida de `algorithm.py` y `signature.py` de shazamio 0.4.0.1
 * (https://github.com/shazamio/ShazamIO, licencia MIT, © 2021 dotX12), que a su vez documenta
 * el formato que descubrió SongRec. Una corrección: shazamio nunca llena la banda de
 * 3,5–5,5 kHz (`5500 < f <= 5500`); aquí va como en SongRec, `3500 < f <= 5500`.
 *
 * Entra PCM de 16 bits, mono, a 16 kHz; sale un mensaje binario con los picos del espectrograma
 * por bandas de frecuencia. No se manda audio, sólo esos picos.
 */
class ShazamSignature private constructor(
    val numberSamples: Int,
    private val peaks: Map<Int, List<Peak>>,
) {
    data class Peak(val fftPass: Int, val magnitude: Int, val correctedBin: Int)

    val peakCounts: Map<Int, Int> get() = peaks.mapValues { it.value.size }

    fun encode(): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, list) in peaks.toSortedMap()) {
            val body = ByteArrayOutputStream()
            var pass = 0
            for (p in list) {
                if (p.fftPass - pass >= 255) {
                    body.write(0xFF)
                    body.write(le32(p.fftPass))
                    pass = p.fftPass
                }
                body.write(p.fftPass - pass)
                body.write(le16(p.magnitude))
                body.write(le16(p.correctedBin))
                pass = p.fftPass
            }
            val bytes = body.toByteArray()
            contents.write(le32(0x60030040 + band))
            contents.write(le32(bytes.size))
            contents.write(bytes)
            contents.write(ByteArray((4 - bytes.size % 4) % 4))
        }
        val c = contents.toByteArray()
        val out = ByteBuffer.allocate(48 + 8 + c.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xCAFE2580.toInt())        // magic1
        out.putInt(0)                         // crc32, se rellena al final
        out.putInt(c.size + 8)                // tamaño sin la cabecera
        out.putInt(0x94119C00.toInt())        // magic2
        out.putInt(0); out.putInt(0); out.putInt(0)
        out.putInt(3 shl 27)                  // 16 kHz (SampleRate._16000 = 3)
        out.putInt(0); out.putInt(0)
        out.putInt(numberSamples + (SAMPLE_RATE * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000)
        out.putInt(c.size + 8)
        out.put(c)
        val bytes = out.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc.toInt())
        return bytes
    }

    fun toUri(): String = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encode())

    val sampleMs: Int get() = numberSamples * 1000 / SAMPLE_RATE

    companion object {
        const val SAMPLE_RATE = 16000
        const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

        /** La huella de todo [pcm] (s16 mono 16 kHz). Shazam espera unos 12 s. */
        fun of(pcm: ShortArray): ShazamSignature = Generator().run(pcm)

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    }

    private class Generator {
        private val samples = IntArray(2048)
        private var samplesPos = 0

        private val fftOut = Array(256) { DoubleArray(1025) }
        private var fftPos = 0
        private var fftWritten = 0

        private val spread = Array(256) { DoubleArray(1025) }
        private var spreadPos = 0
        private var spreadWritten = 0

        private val peaks = sortedMapOf<Int, MutableList<Peak>>()
        private val fft = RealFft2048()
        private val frame = DoubleArray(2048)

        fun run(pcm: ShortArray): ShazamSignature {
            var processed = 0
            while (pcm.size - processed >= 128) {
                for (i in 0 until 128) samples[samplesPos + i] = pcm[processed + i].toInt()
                samplesPos = (samplesPos + 128) % 2048
                doFft()
                doPeakSpreading()
                if (spreadWritten >= 46) doPeakRecognition()
                processed += 128
            }
            return ShazamSignature(processed, peaks)
        }

        private fun doFft() {
            for (i in 0 until 2048) frame[i] = HANNING[i] * samples[(samplesPos + i) % 2048]
            val out = fftOut[fftPos]
            fft.powerSpectrum(frame, out)
            for (i in 0 until 1025) out[i] = max(out[i] / (1 shl 17), 1e-10)
            fftPos = (fftPos + 1) % 256
            fftWritten++
        }

        private fun doPeakSpreading() {
            val last = fftOut[(fftPos - 1 + 256) % 256]
            val spreadLast = DoubleArray(1025)
            for (i in 0 until 1022) spreadLast[i] = max(last[i], max(last[i + 1], last[i + 2]))
            for (i in 1022 until 1025) spreadLast[i] = last[i]
            val i1 = (spreadPos - 1 + 256) % 256
            val i2 = (spreadPos - 3 + 256) % 256
            val i3 = (spreadPos - 6 + 256) % 256
            val a = spread[i1]; val b = spread[i2]; val c = spread[i3]
            for (k in 0 until 1025) {
                val t1 = max(spreadLast[k], a[k])
                val t2 = max(t1, b[k])
                val t3 = max(t2, c[k])
                a[k] = t1; b[k] = t2; c[k] = t3
            }
            spreadLast.copyInto(spread[spreadPos])
            spreadPos = (spreadPos + 1) % 256
            spreadWritten++
        }

        private fun doPeakRecognition() {
            val f46 = fftOut[((fftPos - 46) % 256 + 256) % 256]
            val s49 = spread[((spreadPos - 49) % 256 + 256) % 256]
            for (bin in 10 until 1015) {
                val v = f46[bin]
                if (v < 1.0 / 64 || v < s49[bin - 1]) continue
                var maxNeighbor = 0.0
                for (off in FREQ_NEIGHBORS) maxNeighbor = max(s49[bin + off], maxNeighbor)
                if (v <= maxNeighbor) continue
                var maxOther = maxNeighbor
                for (off in TIME_NEIGHBORS) {
                    maxOther = max(spread[((spreadPos + off) % 256 + 256) % 256][bin - 1], maxOther)
                }
                if (v <= maxOther) continue

                val fftNumber = spreadWritten - 46
                val mag = ln(max(1.0 / 64, v)) * 1477.3 + 6144
                val before = ln(max(1.0 / 64, f46[bin - 1])) * 1477.3 + 6144
                val after = ln(max(1.0 / 64, f46[bin + 1])) * 1477.3 + 6144
                val variation1 = mag * 2 - before - after
                if (variation1 <= 0) continue
                val variation2 = (after - before) * 32 / variation1
                val corrected = bin * 64 + variation2
                val hz = corrected * (SAMPLE_RATE / 2.0 / 1024 / 64)
                val band = when {
                    hz > 250 && hz < 520 -> 0
                    hz > 520 && hz < 1450 -> 1
                    hz > 1450 && hz < 3500 -> 2
                    hz > 3500 && hz <= 5500 -> 3
                    else -> continue
                }
                peaks.getOrPut(band) { mutableListOf() } += Peak(fftNumber, mag.toInt(), corrected.toInt())
            }
        }
    }
}

// np.hanning(2050)[1:-1]: la ventana de Hann sin los ceros de los extremos.
private val HANNING = DoubleArray(2048) { 0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049) }
private val FREQ_NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
private val TIME_NEIGHBORS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

/** FFT real de 2048 puntos (radix 2, iterativa): |X[k]|² para k = 0…1024. */
private class RealFft2048 {
    private val n = 2048
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = 11
        for (i in 0 until n) {
            var x = i; var y = 0
            repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    fun powerSpectrum(input: DoubleArray, out: DoubleArray) {
        for (i in 0 until n) { re[rev[i]] = input[i]; im[rev[i]] = 0.0 }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        for (k in 0..n / 2) out[k] = re[k] * re[k] + im[k] * im[k]
    }
}
