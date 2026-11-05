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
import androidx.media3.session.SessionToken
import es.altamirano.fmdroid.R
import es.altamirano.fmdroid.data.Station
import es.altamirano.fmdroid.playback.RadioService
import es.altamirano.fmdroid.playback.toRequestItem

/** Las órdenes de la app al servicio, encoladas hasta que el MediaController conecta. */
class PlayerController(context: Context) {
    private val future = MediaController.Builder(
        context, SessionToken(context, ComponentName(context, RadioService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    init {
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.let { c -> pending.forEach { it(c) } }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun send(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    fun play(station: Station) = send {
        it.setMediaItem(station.toRequestItem())
        it.prepare()
        it.play()
    }

    fun toggle() = send {
        if (it.playWhenReady) it.pause()
        else {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun stop() = send {
        it.stop()
        it.clearMediaItems()
    }

    fun release() = MediaController.releaseFuture(future)
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
