package es.altamirano.fmdroid.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import es.altamirano.fmdroid.core.INTRO_PREFIX_LENGTH
import es.altamirano.fmdroid.core.IcyReframer
import es.altamirano.fmdroid.core.StreamIntro
import es.altamirano.fmdroid.core.introLength
import es.altamirano.fmdroid.core.introNeedsLearning
import es.altamirano.fmdroid.data.StationMemory
import es.altamirano.fmdroid.net.Http
import es.altamirano.fmdroid.net.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

private const val CAPTURE_BYTES = 1_500_000
private const val CAPTURE_MS = 30_000L
private const val SECOND_CAPTURE_DELAY_MS = 4_000L

/** Hasta 1,5 MB o 30 s de audio en bruto, sin metadatos ICY para que los bytes casen. */
internal fun captureStream(url: String): ByteArray? {
    val request = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Encoding", "identity")
        .build()
    val data = ByteArrayOutputStream()
    val deadline = System.currentTimeMillis() + CAPTURE_MS
    try {
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val input = response.body?.byteStream() ?: return null
            val chunk = ByteArray(16 * 1024)
            while (data.size() < CAPTURE_BYTES && System.currentTimeMillis() < deadline) {
                val n = input.read(chunk)
                if (n < 0) break
                data.write(chunk, 0, n)
            }
        }
    } catch (e: IOException) {
        return data.toByteArray().takeIf { it.isNotEmpty() }
    }
    return data.toByteArray()
}

/** Mide la cuña con dos conexiones abiertas a 4 s una de otra. Tarda unos 35 s. */
internal suspend fun measureIntro(url: String): StreamIntro? = coroutineScope {
    val first = async(Dispatchers.IO) { captureStream(url) }
    val second = async(Dispatchers.IO) {
        delay(SECOND_CAPTURE_DELAY_MS)
        captureStream(url)
    }
    val a = first.await() ?: return@coroutineScope null
    val b = second.await() ?: return@coroutineScope null
    val length = introLength(a, b)
    StreamIntro(length, if (length > 0) a.copyOf(minOf(INTRO_PREFIX_LENGTH, a.size)) else ByteArray(0),
        System.currentTimeMillis() / 1000.0)
}

/** Qué cuña tiene cada emisora y cuándo volver a medirla. */
class Intros(
    private val scope: CoroutineScope,
    private val memory: StationMemory,
    private val foreground: () -> Boolean,
) {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** La cuña que quitar de esta URL, si tiene. Lo llama el hilo de carga de ExoPlayer. */
    fun intro(url: String): StreamIntro? = memory.intro(url)?.takeIf { it.length > 0 }

    /**
     * Medir son unos 2 MB y dos conexiones más: sólo con la app a la vista, como Shazam, por los
     * datos móviles. Lo medido sirve luego también en segundo plano y en el coche.
     */
    fun learnIfNeeded(url: String) {
        if (!foreground() || url.lowercase().substringBefore('?').endsWith(".m3u8")) return
        if (!introNeedsLearning(memory.intro(url), System.currentTimeMillis() / 1000.0)) return
        if (!running.add(url)) return
        scope.launch {
            try {
                measureIntro(url)?.let { memory.recordIntro(url, it) }
            } finally {
                running.remove(url)
            }
        }
    }

    /** La conexión empezó con otros bytes: la cuña ha cambiado y lo recordado ya no vale. */
    fun mismatch(url: String) {
        memory.forgetIntro(url)
        learnIfNeeded(url)
    }
}

/**
 * Lo que lee ExoPlayer: la emisora tal cual, salvo que tenga una cuña medida. Va por debajo del
 * `IcyDataSource` de ExoPlayer, y por eso [IcyReframer] devuelve el entramado ICY intacto.
 */
@UnstableApi
class IntroSkipDataSource(
    private val upstream: DataSource,
    private val intros: Intros,
) : DataSource {
    private var url = ""
    private var reframer: IcyReframer? = null
    private var reported = false
    private val chunk = ByteArray(16 * 1024)
    private var pending = ByteArray(0)
    private var pendingAt = 0

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        url = dataSpec.uri.toString()
        pending = ByteArray(0)
        pendingAt = 0
        reported = false
        // La cuña sólo está al principio de una conexión a un stream sin final conocido.
        val intro = if (dataSpec.position == 0L && length == C.LENGTH_UNSET.toLong()) intros.intro(url) else null
        reframer = intro?.let { IcyReframer(metaint(), it) }
        return length
    }

    private fun metaint(): Int = upstream.responseHeaders.entries
        .firstOrNull { it.key.equals("icy-metaint", ignoreCase = true) }
        ?.value?.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val r = reframer ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        // Mientras se tira la cuña no sale nada: hay que seguir atento a que se cancele la carga.
        while (pendingAt >= pending.size) {
            if (Thread.interrupted()) throw InterruptedIOException()
            val n = upstream.read(chunk, 0, chunk.size)
            if (n == C.RESULT_END_OF_INPUT) return n
            pending = r.feed(chunk, n)
            pendingAt = 0
            if (r.mismatch && !reported) {
                reported = true
                intros.mismatch(url)
            }
        }
        val n = minOf(length, pending.size - pendingAt)
        System.arraycopy(pending, pendingAt, buffer, offset, n)
        pendingAt += n
        return n
    }

    override fun getUri() = upstream.uri
    override fun getResponseHeaders() = upstream.responseHeaders

    override fun close() {
        reframer = null
        pending = ByteArray(0)
        pendingAt = 0
        upstream.close()
    }
}
