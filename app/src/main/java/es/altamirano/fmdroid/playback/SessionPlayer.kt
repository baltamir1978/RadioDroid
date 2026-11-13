package es.altamirano.fmdroid.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi

/**
 * El reproductor tal como lo ven la pantalla de bloqueo, la notificación y Android Auto.
 *
 * - **El tiempo es el de la canción**, no el que lleva sonando la emisora: [song] da por dónde va
 *   y cuánto dura (ms), y si no se sabe, no hay tiempo (como la letra, que no resalta nada).
 * - **Anterior / siguiente cambian de emisora** ([step]), como los botones de CarPlay en RadioApp.
 * - En la radio no se puede ir hacia atrás ni adelante dentro de la canción: sin barra que arrastrar.
 * - **Volver a conectar no es «parado» ni «error»** ([reconnecting]): es «cargando». Android Auto,
 *   al ver parado o error, quita la pantalla de lo que suena y deja la lista de emisoras; pasaba
 *   al volver de una llamada (la conexión nueva al directo pasa por «parado») y en cada corte de red.
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
    private val listeners = mutableMapOf<Player.Listener, SessionListener>()

    override fun addListener(listener: Player.Listener) {
        val wrapped = SessionListener(listener, ::commands,
            state = ::shownState, error = { if (reconnecting()) null else it },
            idle = { super.getPlaybackState() == STATE_IDLE })
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

    /**
     * Parado pero con ganas de sonar y una emisora puesta: el servicio está volviendo a conectar
     * (al directo tras una pausa o una llamada, o reintentando tras un corte), y no para.
     */
    private fun reconnecting(): Boolean =
        super.getPlaybackState() == STATE_IDLE && playWhenReady && mediaItemCount > 0

    private fun shownState(real: Int): Int = if (real == STATE_IDLE && reconnecting()) STATE_BUFFERING else real

    override fun getPlaybackState(): Int = shownState(super.getPlaybackState())
    override fun getPlayerError(): PlaybackException? = if (reconnecting()) null else super.getPlayerError()

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

/**
 * Oyente de la sesión: lo reenvía todo y cambia sólo los comandos disponibles.
 *
 * **No sirve `Player.Listener by inner`**: todos los métodos de esa interfaz son `default` de Java
 * y Kotlin no los delega, así que la sesión no se enteraba de nada. Se quedaba con
 * `playWhenReady = false` y se lo decía a la app, cuyo botón necesitaba dos toques para pausar.
 */
@UnstableApi
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class SessionListener(
    private val inner: Player.Listener,
    private val commands: (Player.Commands) -> Player.Commands,
    /** El estado que se enseña en vez del real («parado» que es «volviendo a conectar»). */
    private val state: (Int) -> Int = { it },
    /** El error que se enseña: ninguno mientras se reintenta. */
    private val error: (PlaybackException?) -> PlaybackException? = { it },
    /** ¿El reproductor de verdad está parado? Entonces lo que se enseña depende de `playWhenReady`. */
    private val idle: () -> Boolean = { false },
) : Player.Listener {
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
        inner.onAvailableCommandsChanged(commands(availableCommands))

    override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = inner.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = inner.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = inner.onMediaMetadataChanged(mediaMetadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
        inner.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
        inner.onPlayerStateChanged(playWhenReady, state(playbackState))
    override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(state(playbackState))
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        inner.onPlayWhenReadyChanged(playWhenReady, reason)
        // Parado, pausar o seguir cambia lo que se enseña (parado ↔ cargando) sin que el
        // reproductor avise de ningún cambio de estado.
        if (idle()) inner.onPlaybackStateChanged(state(Player.STATE_IDLE))
    }
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
        inner.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) { this.error(error)?.let(inner::onPlayerError) }
    override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(this.error(error))
    override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        inner.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = inner.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
        inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onVideoSizeChanged(videoSize: VideoSize) = inner.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
    override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = inner.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onCues(cues: List<Cue>) = inner.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = inner.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = inner.onMetadata(metadata)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = inner.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
}
