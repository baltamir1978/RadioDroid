package es.altamirano.fmdroid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlesTest {
    @Test fun splitsArtistAndTrack() =
        assertEquals(StreamTitle("Carolina", "M-Clan"), Titles.parse("M-Clan - Carolina"))

    @Test fun bareHyphenDoesNotBreakBandNames() =
        assertEquals(StreamTitle("Carolina", "M-Clan"), Titles.parse("M-Clan - Carolina"))

    @Test fun bareHyphenSplitsWhenNoSpacedOne() =
        assertEquals(StreamTitle("Song", "Artist"), Titles.parse("Artist-Song"))

    @Test fun rotationCodesAreNotSongs() = assertNull(Titles.parse("0042 1234"))

    @Test fun stationSloganIsNotASong() =
        assertNull(Titles.parse("La Mejor Variedad Musical - CADENA 100", "Cadena 100"))

    @Test fun collaborationsSplit() =
        assertEquals(listOf("El Canto del Loco", "Amaia Montero"), Titles.artistNames("El Canto del Loco y Amaia Montero"))

    @Test fun featSplits() = assertEquals(listOf("A", "B"), Titles.artistNames("A feat. B"))

    @Test fun ampersandAndYAreTheSameBand() =
        assertEquals(Titles.artistKey("Fito y Fitipaldis"), Titles.artistKey("Fito & Fitipaldis"))

    @Test fun swappedTitleDetectedByItunesArtist() {
        // Cassette FM: «Título - Artista»
        val t = Titles.parse("Bailando - Alaska y Dinarama")!!
        assertTrue(Titles.isSwapped(t, "Alaska y Dinarama"))
        assertFalse(Titles.isSwapped(t.swapped(), "Alaska y Dinarama"))
    }

    @Test fun sameSongIgnoresDecorations() = assertTrue(Titles.sameSong("Miedo (Directo)", "Miedo"))
}

class ClockTest {
    @Test fun firstTitleIsNotExact() {
        val c = SongClock().titleChanged(100.0, 2.0, seenTitleBefore = false)
        assertEquals(98.0, c.startedAt!!, 1e-9)
        assertFalse(c.exact)
    }

    @Test fun elapsedIncludesLeadAndOffset() {
        val c = SongClock(offset = 0.5).titleChanged(100.0, 0.0, true)
        assertEquals(10.0 + LYRICS_LEAD + 0.5, c.elapsed(110.0)!!, 1e-9)
    }

    @Test fun tapOnLineSyncsThatLine() {
        for (exact in listOf(true, false)) {
            val c = SongClock(startedAt = 100.0, exact = exact).syncToLine(lineTime = 30.0, tappedAt = 140.0)
            // Tras tocar, en ese instante se está en la línea tocada (+ el tiempo de reacción).
            assertEquals(30.3, c.elapsed(140.0)!!, 1e-9)
        }
    }

    @Test fun offsetIsClamped() = assertEquals(MAX_OFFSET, SongClock().nudge(100.0).offset, 1e-9)

    @Test fun implausibleLagIsNotLearned() {
        assertNull(learnTitleLag(null, 45.0))
        assertEquals(3.0, learnTitleLag(2.0, 4.0)!!, 1e-9)
    }
}

class LrcTest {
    @Test fun parsesStampsAndRepeatedLines() {
        val lines = Lrc.parse("[ar:x]\n[00:10.00][00:30.50]Estribillo\n[00:20.00]Verso")
        assertEquals(listOf(10.0, 20.0, 30.5), lines.map { it.time })
        assertEquals("Estribillo", lines[2].text)
    }

    @Test fun offsetTagShiftsEverything() {
        val lines = Lrc.parse("[offset:+500]\n[00:10.00]a")
        assertEquals(9.5, lines[0].time, 1e-9)
    }

    @Test fun lineIndex() {
        val l = SongLyrics(synced = Lrc.parse("[00:10.00]a\n[00:20.00]b"))
        assertNull(l.lineIndex(5.0))
        assertEquals(0, l.lineIndex(15.0))
        assertEquals(1, l.lineIndex(25.0))
    }

    @Test fun stripsDecorations() = assertEquals("Song ", Lrc.stripDecorations("Song (Radio Edit)"))

    @Test fun pickPrefersSyncedAndRejectsOtherSongs() {
        val hits = Json.parseToJsonElement("""[
            {"trackName":"Otra","artistName":"X","duration":200,"syncedLyrics":"[00:01.00]no"},
            {"trackName":"Puede ser","artistName":"El Canto del Loco","duration":210,"plainLyrics":"texto"},
            {"trackName":"Puede Ser (Remastered)","artistName":"El Canto del Loco","duration":211,"syncedLyrics":"[00:01.00]sí"},
            {"trackName":"Puede ser","artistName":"Basura","duration":20,"syncedLyrics":"[00:01.00]corta"}
        ]""") as JsonArray
        val best = Lrc.pick(Lrc.hitsOf(hits), "Puede ser", listOf("El Canto del Loco", "Amaia Montero"))
        assertNotNull(best)
        assertEquals("sí", Lrc.fromHit(best!!)!!.synced.single().text)
    }
}
