package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sledovanie zmien - z toho vznikaju notifikacie v Android appke.
 *
 * Prve kolo nesmie hlasit nic (nie je s cim porovnavat), inak by appka pri
 * kazdom spusteni vypalila notifikaciu o "zmene", ktora sa nestala.
 */
class SeatWatcherTest {

    private fun snapshot(vararg pairs: Pair<Int, Boolean>) = SeatSnapshot(mapOf(*pairs))

    @Test
    fun `prve kolo nehlasi nic`() {
        val alerts = SeatWatcher.diff(previous = null, current = snapshot(31 to false, 33 to true))
        assertTrue(alerts.isEmpty(), "bez predchadzajuceho stavu nie je co hlasit")
    }

    @Test
    fun `uvolnene a obsadene miesto sa rozlisia`() {
        val before = snapshot(31 to false, 33 to true, 35 to false)
        val after = snapshot(31 to true, 33 to false, 35 to false)
        val alerts = SeatWatcher.diff(before, after)
        assertEquals(2, alerts.size)
        assertEquals(SeatChange.FREED, alerts.first { it.seat == 31 }.change)
        assertEquals(SeatChange.TAKEN, alerts.first { it.seat == 33 }.change)
    }

    @Test
    fun `bez zmeny ziadny alert`() {
        val s = snapshot(31 to false, 33 to true)
        assertTrue(SeatWatcher.diff(s, s).isEmpty())
    }

    @Test
    fun `nove miesto v snapshote nie je zmena`() {
        // vozen sa mohol zmenit alebo API vratilo iny rozsah miest - to nie je
        // udalost, o ktorej ma zmysel budit uzivatela
        val before = snapshot(31 to false)
        val after = snapshot(31 to false, 33 to true)
        assertTrue(SeatWatcher.diff(before, after).isEmpty())
    }

    @Test
    fun `vypredany spoj ktory sa uvolnil sa ohlasi`() {
        val before = SeatSnapshot(emptyMap(), freeInCoach = 0)
        val after = SeatSnapshot(emptyMap(), freeInCoach = 3)
        assertTrue(SeatWatcher.soldOutOpenedUp(before, after), "storno vo vypredanom spoji sa ma ohlasit")
        assertTrue(!SeatWatcher.soldOutOpenedUp(after, after))
    }

    // --- ktore konkretne miesta vo vozni sa uvolnili -------------------
    // Notifikacia "uvolnilo sa miesto vo vozni 7" bez cisla miesta sa neda
    // pouzit - clovek nevie, kam si ma sadnut.

    @Test
    fun `uvolnene miesta vo vozni sa vymenuju cislami`() {
        val before = SeatSnapshot(emptyMap(), freeInCoach = 1, coachFreeSeats = setOf(12))
        val after = SeatSnapshot(emptyMap(), freeInCoach = 3, coachFreeSeats = setOf(12, 31, 45))
        assertEquals(listOf(31, 45), SeatWatcher.coachFreed(before, after))
    }

    @Test
    fun `prve kolo nehlasi uvolnene miesta vo vozni`() {
        val after = SeatSnapshot(emptyMap(), freeInCoach = 3, coachFreeSeats = setOf(1, 2, 3))
        assertTrue(SeatWatcher.coachFreed(null, after).isEmpty())
    }

    @Test
    fun `obsadenie miesta vo vozni nie je uvolnenie`() {
        val before = SeatSnapshot(emptyMap(), freeInCoach = 2, coachFreeSeats = setOf(5, 6))
        val after = SeatSnapshot(emptyMap(), freeInCoach = 1, coachFreeSeats = setOf(5))
        assertTrue(SeatWatcher.coachFreed(before, after).isEmpty())
    }

    @Test
    fun `uvolnene miesta su usporiadane vzestupne`() {
        val before = SeatSnapshot(emptyMap(), coachFreeSeats = emptySet())
        val after = SeatSnapshot(emptyMap(), coachFreeSeats = setOf(45, 12, 31))
        assertEquals(listOf(12, 31, 45), SeatWatcher.coachFreed(before, after))
    }

    @Test
    fun `zhrnutie pre notifikaciu skrati dlhy zoznam`() {
        val many = (1..12).toList()
        val text = SeatWatcher.describeSeats(many, max = 5)
        assertTrue(text.startsWith("1, 2, 3, 4, 5"), "ma zacinat prvymi cislami: $text")
        assertTrue("+7" in text, "ma naznacit kolko dalsich: $text")
        assertEquals("1, 2", SeatWatcher.describeSeats(listOf(1, 2), max = 5))
        assertEquals("", SeatWatcher.describeSeats(emptyList(), max = 5))
    }
}
