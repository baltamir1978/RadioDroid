package es.altamirano.fmdroid.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lo que suena, copiado del render de ExoPlayer (un `TeeAudioProcessor`): sin abrir una segunda
 * conexión a la emisora. Se baja a 16 kHz mono, lo que pide la huella de Shazam, y se guardan
 * los últimos [KEEP_SECONDS] s con el «tiempo de stream» de su final (ver [AudioSample]).
 *
 * El tiempo de stream sólo avanza mientras suena y salta al directo al reconectar, así que la
 * ráfaga de audio atrasado que mandan algunas emisoras al conectar (Cadena 100: 5,2 s) queda en
 * su sitio y no desplaza la posición que dice Shazam. Al cambiar de emisora o reconectar,
 * ExoPlayer llama a `flush` y se tira lo guardado para no mezclar audio de dos conexiones.
 */
@UnstableApi
class AudioTap(private val streamNow: () -> Double) : TeeAudioProcessor.AudioBufferSink {
    private val ring = ShortArray(TARGET_RATE * KEEP_SECONDS)
    private var write = 0
    private var count = 0
    /** Tiempo de stream del final de lo guardado. */
    private var endStream = 0.0

    // Formato de entrada y estado del remuestreo
    private var inRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var step = inRate / TARGET_RATE.toDouble()
    private var phase = 0.0
    private var previous = 0.0
    private var lowPass = Biquad.lowPass(inRate, CUTOFF_HZ)
    private var lowPass2 = Biquad.lowPass(inRate, CUTOFF_HZ)

    @Synchronized
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        inRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        step = inRate / TARGET_RATE.toDouble()
        phase = 0.0
        previous = 0.0
        lowPass = Biquad.lowPass(inRate, CUTOFF_HZ)
        lowPass2 = Biquad.lowPass(inRate, CUTOFF_HZ)
        count = 0
        write = 0
    }

    @Synchronized
    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return      // otros formatos no salen de ExoPlayer con MP3/AAC
        }
        val frames = b.remaining() / (bytesPerSample * channels)
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                sum += if (bytesPerSample == 2) b.short.toDouble() else b.float * 32767.0
            }
            // Mono, y un paso bajo a 7 kHz para que lo de arriba no se cuele al bajar a 16 kHz.
            val x = lowPass2.next(lowPass.next(sum / channels))
            // Interpolación lineal entre la muestra anterior y esta.
            while (phase < 1.0) {
                val y = previous + (x - previous) * phase
                ring[write] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
                write = (write + 1) % ring.size
                if (count < ring.size) count++
                phase += step
            }
            phase -= 1.0
            previous = x
        }
        endStream = streamNow()
    }

    /** Segundos seguidos que hay guardados. */
    @get:Synchronized
    val seconds: Double get() = count / TARGET_RATE.toDouble()

    /** Tiempo de stream del final de lo guardado. */
    @get:Synchronized
    val endTime: Double get() = endStream

    /**
     * Los últimos [secs] s (o menos, si no hay tantos), y el tiempo de stream en que empiezan:
     * es el punto al que se refiere el `offset` que devuelve Shazam.
     */
    @Synchronized
    fun snapshot(secs: Double): Pair<ShortArray, Double>? {
        val n = minOf(count, (secs * TARGET_RATE).toInt())
        if (n <= 0) return null
        val out = ShortArray(n)
        val start = (write - n + ring.size) % ring.size
        for (i in 0 until n) out[i] = ring[(start + i) % ring.size]
        return out to endStream - n / TARGET_RATE.toDouble()
    }

    companion object {
        const val TARGET_RATE = 16000
        const val KEEP_SECONDS = 14
        private const val CUTOFF_HZ = 7000.0
    }
}

/** Filtro de segundo orden (Butterworth), para el paso bajo antes de bajar de frecuencia. */
private class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun next(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        fun lowPass(rate: Int, cutoff: Double): Biquad {
            val f = minOf(cutoff, rate * 0.45)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * (1 / sqrt(2.0)))
            val a0 = 1 + alpha
            val c = cos(w)
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
