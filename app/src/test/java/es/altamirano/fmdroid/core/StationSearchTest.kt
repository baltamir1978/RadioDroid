package es.altamirano.fmdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationSearchTest {
    private val names = listOf("Radio 3", "Cadena 100", "LOS40 Classic", "La Indie", "Rock FM", "Radio Clásica")

    @Test fun exactNameWinsIgnoringCaseAndAccents() {
        assertEquals(0, matchStation("radio 3", names))
        assertEquals(5, matchStation("radio clasica", names))
        assertEquals(1, matchStation("CADENA 100", names))
    }

    @Test fun partOfTheNameOrALongerPhrase() {
        assertEquals(3, matchStation("indie", names))
        assertEquals(2, matchStation("los 40 classic", names))
        assertEquals(4, matchStation("rock", names))
    }

    @Test fun sharedWordsWhenNothingContainsTheOther() {
        assertEquals(1, matchStation("la cadena de música 100", names))
    }

    @Test fun nothingToMatch() {
        assertNull(matchStation(null, names))
        assertNull(matchStation("  ", names))
        assertNull(matchStation("jazz", names))
        assertNull(matchStation("radio 3", emptyList()))
    }
}
