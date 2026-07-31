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
}
