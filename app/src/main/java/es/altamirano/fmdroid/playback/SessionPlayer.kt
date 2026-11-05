package es.altamirano.fmdroid.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * El reproductor tal como lo ven la pantalla de bloqueo, la notificación y Android Auto.
 *
 * - **El tiempo es el de la canción**, no el que lleva sonando la emisora: [song] da por dónde va
 *   y cuánto dura (ms), y si no se sabe, no hay tiempo (como la letra, que no resalta nada).
 * - **Anterior / siguiente cambian de emisora** ([step]), como los botones de CarPlay en RadioApp.
 * - En la radio no se puede ir hacia atrás ni adelante dentro de la canción: sin barra que arrastrar.
 *
 * ExoPlayer sigue siendo el de verdad: el servicio le habla directamente (reloj de la letra,
 * reconexión); esto es sólo la cara que ve el sistema.
 */
@UnstableApi
class SessionPlayer(
    player: Player,
    private val song: () -> Pair<Long, Long>?,
    private val step: (Int) -> Unit,
) : ForwardingPlayer(player) {

    /** Los oyentes de la sesión, para darles los comandos que tiene esta cara y avisar del tiempo. */
    private val listeners = mutableMapOf<Player.Listener, Listener>()

    private inner class Listener(val inner: Player.Listener) : Player.Listener by inner {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            inner.onAvailableCommandsChanged(commands(availableCommands))
    }

    override fun addListener(listener: Player.Listener) {
        val wrapped = Listener(listener)
        listeners[listener] = wrapped
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    private fun commands(base: Player.Commands): Player.Commands {
        val b = base.buildUpon().removeAll(
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD,
        )
        // Con una emisora puesta, se puede pasar a la de al lado.
        if (base.contains(COMMAND_PLAY_PAUSE)) b.addAll(
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        return b.build()
    }

    override fun getAvailableCommands(): Player.Commands = commands(super.getAvailableCommands())
    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem() = mediaItemCount > 0
    override fun hasPreviousMediaItem() = mediaItemCount > 0
    override fun seekToNext() = step(1)
    override fun seekToNextMediaItem() = step(1)
    override fun seekToPrevious() = step(-1)
    override fun seekToPreviousMediaItem() = step(-1)

    // Sin canción situada, posición desconocida (−1, `PLAYBACK_POSITION_UNKNOWN`): Android Auto
    // no cuenta nada en vez de contar desde que se puso la emisora.
    override fun getCurrentPosition(): Long = song()?.first ?: UNKNOWN
    override fun getContentPosition(): Long = currentPosition
    override fun getBufferedPosition(): Long = song()?.first ?: 0
    override fun getContentBufferedPosition(): Long = bufferedPosition
    override fun getTotalBufferedDuration(): Long = 0
    override fun getDuration(): Long = song()?.second ?: C.TIME_UNSET
    override fun getContentDuration(): Long = duration
    override fun isCurrentMediaItemSeekable(): Boolean = false
    // En directo, el sistema no muestra duración: con la canción situada, se muestra la suya.
    override fun isCurrentMediaItemLive(): Boolean = song() == null && super.isCurrentMediaItemLive()

    /** La canción se ha situado o ha cambiado el ajuste: que el sistema rehaga su reloj. */
    fun songClockChanged() {
        val index = currentMediaItemIndex
        val pos = currentPosition
        val info = Player.PositionInfo(null, index, currentMediaItem, null, index, pos, pos,
            C.INDEX_UNSET, C.INDEX_UNSET)
        val meta = mediaMetadata
        listeners.keys.toList().forEach {
            it.onPositionDiscontinuity(info, info, DISCONTINUITY_REASON_INTERNAL)
            // La duración de la pantalla de bloqueo y del coche va con los metadatos.
            it.onMediaMetadataChanged(meta)
        }
    }

    private companion object {
        const val UNKNOWN = -1L
    }
}
