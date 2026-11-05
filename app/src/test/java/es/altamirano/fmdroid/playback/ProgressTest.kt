package es.altamirano.fmdroid.playback

import es.altamirano.fmdroid.core.SongClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    private val song = Song("Canción", "Artista", duration = 200.0)

    @Test fun sinPrincipioExactoNoHayTiempo() {
        val s = NowState(song = song, clock = SongClock(startedAt = 100.0, exact = false))
        assertNull(s.progress(150.0))
    }

    @Test fun sinDuracionNoHayTiempo() {
        val s = NowState(song = song.copy(duration = null), clock = SongClock(startedAt = 100.0, exact = true))
        assertNull(s.progress(150.0))
    }

    @Test fun cuentaDesdeElPrincipioConElAjuste() {
        val s = NowState(song = song, clock = SongClock(startedAt = 100.0, exact = true, offset = 1.5))
        assertEquals(51.5 to 200.0, s.progress(150.0))
    }

    @Test fun pasadaLaDuracionSeQuedaAlFinalYLuegoDesaparece() {
        val s = NowState(song = song, clock = SongClock(startedAt = 100.0, exact = true))
        assertEquals(200.0 to 200.0, s.progress(305.0))
        assertNull(s.progress(320.0))
    }
}
