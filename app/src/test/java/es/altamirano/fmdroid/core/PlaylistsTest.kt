package es.altamirano.fmdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {
    @Test fun reconoceListasPorLaRuta() {
        assertTrue(Playlists.isPlaylistUrl("http://radio.example/listen.pls"))
        assertTrue(Playlists.isPlaylistUrl("https://radio.example/stream.M3U?sid=1"))
        assertTrue(Playlists.isPlaylistUrl(" http://radio.example/a.pls#x "))
        assertFalse(Playlists.isPlaylistUrl("https://radio.example/live.m3u8"))
        assertFalse(Playlists.isPlaylistUrl("https://radio.example/stream.mp3"))
        assertFalse(Playlists.isPlaylistUrl("https://radio.example/stream?f=.pls/x"))
    }

    @Test fun plsEnElOrdenDeSusNumeros() {
        val pls = """
            [playlist]
            NumberOfEntries=2
            File2=http://backup.example:8000/live
            Title1=Radio
            File1=https://main.example/live
            Length1=-1
            Version=2
        """.trimIndent()
        assertEquals(listOf("https://main.example/live", "http://backup.example:8000/live"), Playlists.entries(pls))
    }

    @Test fun plsSinCabeceraYConEspacios() {
        assertEquals(listOf("http://a.example/;"), Playlists.entries("file1 = http://a.example/;\r\n"))
    }

    @Test fun m3uSaltaComentariosYRutasLocales() {
        val m3u = "\uFEFF#EXTM3U\r\n#EXTINF:-1,Radio\r\n\r\nC:\\musica\\x.mp3\r\nhttp://stream.example/aac\r\nhttps://otro.example/mp3\r\n"
        assertEquals(listOf("http://stream.example/aac", "https://otro.example/mp3"), Playlists.entries(m3u))
    }

    @Test fun listaVacia() {
        assertEquals(emptyList<String>(), Playlists.entries("#EXTM3U\n"))
        assertEquals(emptyList<String>(), Playlists.entries(""))
    }
}
