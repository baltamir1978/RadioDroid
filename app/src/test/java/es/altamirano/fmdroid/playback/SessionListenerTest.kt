package es.altamirano.fmdroid.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

class SessionListenerTest {
    /** Kotlin no delega los métodos `default` de Java: cada uno tiene que estar escrito a mano. */
    @Test fun reenviaTodosLosMetodosDeLaInterfaz() {
        val missing = Player.Listener::class.java.methods
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { m ->
                runCatching { SessionListener::class.java.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure
            }
            .map { it.name }
        assertTrue("Sin reenviar: $missing", missing.isEmpty())
    }

    @Test fun laSesionSeEnteraDePlayYPausa() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val inner = Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java),
        ) { _, method, args ->
            calls += method.name to args.orEmpty().toList()
            null
        } as Player.Listener
        val listener = SessionListener(inner, { it })

        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onIsPlayingChanged(true)
        listener.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(
            listOf(
                "onPlayWhenReadyChanged" to listOf(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST),
                "onIsPlayingChanged" to listOf(true),
                "onPlaybackStateChanged" to listOf(Player.STATE_READY),
            ),
            calls,
        )
    }

    private fun recorder(calls: MutableList<Pair<String, List<Any?>>>) = Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java),
    ) { _, method, args ->
        calls += method.name to args.orEmpty().toList()
        null
    } as Player.Listener

    /** Android Auto quita la pantalla de lo que suena si ve «parado» o un error. */
    @Test fun reconectarNoEsPararNiError() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        var wantsToPlay = true
        val listener = SessionListener(recorder(calls), { it },
            state = { if (it == Player.STATE_IDLE && wantsToPlay) Player.STATE_BUFFERING else it },
            error = { if (wantsToPlay) null else it },
            idle = { true })
        val error = androidx.media3.common.PlaybackException("corte", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        listener.onPlayerErrorChanged(error)
        listener.onPlayerError(error)
        listener.onPlaybackStateChanged(Player.STATE_IDLE)
        assertEquals(
            listOf("onPlayerErrorChanged" to listOf(null), "onPlaybackStateChanged" to listOf<Any?>(Player.STATE_BUFFERING)),
            calls,
        )

        // Si se pausa mientras reintenta, entonces sí está parado.
        calls.clear()
        wantsToPlay = false
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals("onPlaybackStateChanged" to listOf<Any?>(Player.STATE_IDLE), calls.last())
    }
}
