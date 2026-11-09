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
        val listener = SessionListener(inner) { it }

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
}
