package io.github.mangis14.rjchecker.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kedy ma kontrola na pozadi vobec siahnut na siet.
 *
 * Jedna kontrola stiahne cca 223 kB (freeSeats 130 kB + layout vozna 88 kB +
 * detail 5 kB) a server negzipuje. Pri kontrole kazdych 15 minut je to cca
 * 21 MB denne, takze rozhodovanie "teraz netreba" ma realny dopad na data.
 */
class WatchScheduleTest {

    private val hour = 60L

    @Test
    fun `po odjazde sa sledovanie ukonci`() {
        // Bez tohto by worker tahal data naveky aj po skoncenej ceste.
        assertEquals(
            WatchDecision.STOP,
            WatchSchedule.decide(nowMinutes = 100 * hour, departureMinutes = 90 * hour, tick = 0),
        )
    }

    @Test
    fun `pocas cesty sa kontroluje vzdy`() {
        // hodinu po odjazde je clovek vo vlaku - vtedy je informacia najcennejsia
        assertEquals(
            WatchDecision.CHECK,
            WatchSchedule.decide(nowMinutes = 91 * hour, departureMinutes = 90 * hour, tick = 1),
        )
    }

    @Test
    fun `tesne pred odjazdom sa kontroluje kazdy tick`() {
        for (tick in 0..3) {
            assertEquals(
                WatchDecision.CHECK,
                WatchSchedule.decide(nowMinutes = 89 * hour, departureMinutes = 90 * hour, tick = tick),
                "tick $tick",
            )
        }
    }

    @Test
    fun `viac ako den pred odjazdom sa kontroluje len kazdy stvrty tick`() {
        val departure = 100L * hour
        val now = departure - 48 * hour
        val decisions = (0..7).map { WatchSchedule.decide(now, departure, it) }
        assertEquals(
            listOf(
                WatchDecision.CHECK, WatchDecision.SKIP, WatchDecision.SKIP, WatchDecision.SKIP,
                WatchDecision.CHECK, WatchDecision.SKIP, WatchDecision.SKIP, WatchDecision.SKIP,
            ),
            decisions,
            "dva dni dopredu staci pribliznie raz za hodinu",
        )
    }

    @Test
    fun `neznamy cas odjazdu sa kontroluje normalne`() {
        // radsej kontrolovat, ako tichu prestat - pouzivatel ceka notifikacie
        assertEquals(
            WatchDecision.CHECK,
            WatchSchedule.decide(nowMinutes = 1000, departureMinutes = null, tick = 5),
        )
    }

    @Test
    fun `sledovanie prezije aj nocny spoj s prijazdom na druhy den`() {
        val departure = 100L * hour
        // 5 hodin po odjazde je nocny vlak stale na trati
        assertEquals(
            WatchDecision.CHECK,
            WatchSchedule.decide(departure + 5 * hour, departure, tick = 2),
        )
        // 12 hodin po odjazde uz cesta skoncila
        assertEquals(
            WatchDecision.STOP,
            WatchSchedule.decide(departure + 12 * hour, departure, tick = 2),
        )
    }
}
