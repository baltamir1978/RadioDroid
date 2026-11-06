package es.altamirano.fmdroid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionToken
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.playback.RadioService
import es.altamirano.fmdroid.playback.toRequestItem

/**
 * Las órdenes de la app al servicio, encoladas hasta que el MediaController conecta. Si la
 * conexión se pierde (el sistema mató o reinició el servicio), se rehace en la siguiente orden:
 * antes las órdenes iban a un controlador muerto y la app no hacía caso.
 */
class PlayerController(context: Context) {
    private val context = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    init { connect() }

    private fun connect() {
        val f = MediaController.Builder(context, SessionToken(context, ComponentName(context, RadioService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    // Se suelta y la siguiente orden conecta de nuevo.
                    if (this@PlayerController.controller === controller) {
                        this@PlayerController.controller = null
                        future = null
                    }
                    controller.release()
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            if (future !== f) return@addListener
            controller = runCatching { f.get() }.getOrNull()
            val c = controller
            if (c == null) { future = null; return@addListener }   // la siguiente orden lo intenta otra vez
            pending.toList().forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun send(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) { block(c); return }
        pending.add(block)
        // Sin controlador vivo ni conexión en marcha: se rehace. Con una en marcha (aunque ya haya
        // terminado y su listener esté por correr), se espera: ella vacía la cola.
        if (c != null || future == null) {
            controller = null
            future?.let { MediaController.releaseFuture(it) }
            connect()
        }
    }

    fun play(station: Station) = send {
        it.setMediaItem(station.toRequestItem())
        it.prepare()
        it.play()
    }

    fun toggle() = send {
        // Callada por una llamada que no devolvió el foco: el botón muestra play, y play es
        // pausar y volver a pedir el foco (el servicio, si fue largo, vuelve al directo).
        val suppressed = it.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        if (it.playWhenReady && !suppressed) it.pause()
        else {
            if (suppressed) it.pause()
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun stop() = send {
        it.stop()
        it.clearMediaItems()
    }

    fun release() { future?.let { MediaController.releaseFuture(it) }; future = null; controller = null }
}

/** Accesos directos (pulsación larga en el icono) a las primeras emisoras: `radiodroid://play?u=…`. */
object Shortcuts {
    fun playUri(station: Station): Uri = Uri.Builder().scheme("radiodroid").authority("play")
        .appendQueryParameter("u", station.streamURL)
        .appendQueryParameter("n", station.name)
        .build()

    fun publish(context: Context, stations: List<Station>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
        val shortcuts = stations.take(max).map { s ->
            ShortcutInfoCompat.Builder(context, s.id)
                .setShortLabel(s.name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(Intent.ACTION_VIEW, playUri(s)).setClass(context, MainActivity::class.java))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
